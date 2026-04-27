package com.legalpartner.service.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.extraction.EvidenceSpan;
import com.legalpartner.model.dto.extraction.ExtractionEntry;
import com.legalpartner.service.RiskQuestionEngine;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-pass contract term discovery.
 * Pass 1: Open discovery — LLM reads contract, extracts all material terms.
 * Pass 2: Self-review — "what did I miss?"
 * Pass 3: Targeted per-section — config-driven gap fill using existing section extraction.
 *
 * Model-aware chunking: Gemini gets full doc, vLLM gets 12K windows with 2K overlap.
 */
@Component
@Slf4j
public class DiscoveryPass {

    private final ChatLanguageModel jsonChatModel;
    private final ChatLanguageModel shortChatModel;
    private final RiskQuestionEngine riskQuestionEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${legalpartner.extraction.full-text-cap-chars:12000}")
    private int windowSize;

    @Value("${legalpartner.extraction.overlap-chars:2000}")
    private int overlapChars;

    @Value("${legalpartner.chat.provider:vllm}")
    private String chatProvider;

    public DiscoveryPass(@Qualifier("jsonChatModel") ChatLanguageModel jsonChatModel,
                          @Qualifier("shortChatModel") ChatLanguageModel shortChatModel,
                          RiskQuestionEngine riskQuestionEngine) {
        this.jsonChatModel = jsonChatModel;
        this.shortChatModel = shortChatModel;
        this.riskQuestionEngine = riskQuestionEngine;
    }

    /**
     * Execute all 3 discovery passes.
     * Returns raw extractions (before canonical mapping).
     */
    public List<ExtractionEntry> execute(String fullText, String contractType) {
        List<ExtractionEntry> allDiscovered = new ArrayList<>();

        // Pass 1: Open discovery (chunked for large docs)
        List<ExtractionEntry> pass1 = openDiscovery(fullText);
        allDiscovered.addAll(pass1);
        log.info("Discovery Pass 1: {} terms extracted", pass1.size());

        // Pass 2: Self-review — what did we miss?
        List<ExtractionEntry> pass2 = selfReview(fullText, pass1);
        allDiscovered.addAll(pass2);
        log.info("Discovery Pass 2: {} additional terms found", pass2.size());

        // Pass 3: Targeted extraction for config fields not yet discovered
        Set<String> discoveredNames = allDiscovered.stream()
                .map(e -> e.rawField() != null ? e.rawField().toLowerCase() : "")
                .collect(Collectors.toSet());
        List<ExtractionEntry> pass3 = targetedExtraction(fullText, contractType, discoveredNames);
        allDiscovered.addAll(pass3);
        log.info("Discovery Pass 3: {} targeted terms extracted", pass3.size());

        log.info("Discovery total: {} terms across 3 passes", allDiscovered.size());
        return allDiscovered;
    }

    /** Pass 1: Open discovery with model-aware chunking */
    private List<ExtractionEntry> openDiscovery(String fullText) {
        List<String> chunks = chunkText(fullText);
        List<ExtractionEntry> results = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String chunkLabel = chunks.size() > 1 ? " (chunk " + (i + 1) + "/" + chunks.size() + ")" : "";
            log.debug("Discovery Pass 1{}: {} chars", chunkLabel, chunk.length());

            String prompt = String.format("""
                    You are a contract analyst. Read this contract and extract ALL material terms, provisions, and key data points.

                    For each term found, provide:
                    - field_name: a short descriptive name in snake_case (e.g., "liability_cap", "governing_law", "renewal_terms")
                    - value: the extracted value
                    - evidence: the EXACT quoted text from the contract that contains this term
                    - section_ref: section number if identifiable (e.g., "Section 8.2"), or null

                    Be thorough — extract every date, amount, party name, obligation, restriction, cap, threshold, SLA, penalty, and defined term.

                    Contract text:
                    %s

                    Output ONLY valid JSON:
                    {"terms": [{"field_name": "...", "value": "...", "evidence": "exact quote", "section_ref": "..."}]}
                    """, chunk);

            results.addAll(callAndParse(prompt, "DISCOVERED"));
        }

        return results;
    }

    /** Pass 2: Self-review — find what Pass 1 missed */
    private List<ExtractionEntry> selfReview(String fullText, List<ExtractionEntry> pass1Results) {
        if (pass1Results.isEmpty()) return List.of();

        String foundSummary = pass1Results.stream()
                .map(e -> "- " + e.rawField() + ": " + truncate(e.value(), 40))
                .collect(Collectors.joining("\n"));

        // Use first chunk for context (self-review is about recall, not full coverage)
        String contextText = fullText.length() > windowSize ? fullText.substring(0, windowSize) : fullText;

        String prompt = String.format("""
                You previously extracted these terms from the contract:
                %s

                Review the contract again. What material terms did you MISS? Look especially for:
                - Auto-renewal, extension, or rollover provisions
                - Price escalation or adjustment mechanisms
                - Insurance requirements
                - Audit rights
                - Data residency or data handling obligations
                - Non-compete or non-solicitation restrictions
                - Assignment or transfer limitations
                - Most favored nation clauses
                - Service credits or penalties

                Only output NEW terms not already listed above. Same JSON format.

                Contract text:
                %s

                Output ONLY valid JSON:
                {"terms": [{"field_name": "...", "value": "...", "evidence": "exact quote", "section_ref": "..."}]}
                """, foundSummary, contextText);

        return callAndParse(prompt, "SELF_REVIEW");
    }

    /** Pass 3: Targeted extraction for config fields not yet discovered */
    private List<ExtractionEntry> targetedExtraction(String fullText, String contractType, Set<String> alreadyFound) {
        List<RiskQuestionEngine.ExtractionField> configFields =
                riskQuestionEngine.getExtractionFields(contractType);

        List<ExtractionEntry> results = new ArrayList<>();

        for (RiskQuestionEngine.ExtractionField field : configFields) {
            String fieldId = field.id().toLowerCase().replace("-", "_");
            // Skip if already discovered (fuzzy check on field name)
            if (alreadyFound.stream().anyMatch(f -> f.contains(fieldId) || fieldId.contains(f))) continue;

            // Find relevant section
            String section;
            if (field.usesPreamble()) {
                section = fullText.substring(0, Math.min(3000, fullText.length()));
            } else {
                int start = findClauseStart(fullText, field.sectionKeywords().toArray(new String[0]));
                if (start < 0) continue; // Section not found — will be flagged as gap later
                section = fullText.substring(start, Math.min(start + 3000, fullText.length()));
            }

            String prompt = String.format("""
                    Extract the following from this contract section:
                    - %s: %s

                    Section:
                    %s

                    Output ONLY valid JSON:
                    {"terms": [{"field_name": "%s", "value": "extracted value or null if not found", "evidence": "exact quote or null", "section_ref": "section number or null"}]}
                    """, fieldId, field.description(), section, fieldId);

            try {
                List<ExtractionEntry> extracted = callAndParse(prompt, "TARGETED");
                // Only add if value is not null/empty
                extracted.stream()
                        .filter(e -> e.value() != null && !e.value().isBlank() && !"null".equalsIgnoreCase(e.value()))
                        .forEach(results::add);
            } catch (Exception e) {
                log.debug("Targeted extraction failed for {}: {}", fieldId, e.getMessage());
            }
        }

        return results;
    }

    /** Call LLM and parse JSON response into ExtractionEntry list */
    private List<ExtractionEntry> callAndParse(String prompt, String source) {
        try {
            String response = jsonChatModel.generate(UserMessage.from(prompt)).content().text();

            // Extract JSON from response (handle markdown code fences)
            String json = response;
            if (json.contains("```")) {
                json = json.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "");
            }
            int jsonStart = json.indexOf('{');
            if (jsonStart >= 0) json = json.substring(jsonStart);

            JsonNode root = objectMapper.readTree(json);
            List<ExtractionEntry> entries = new ArrayList<>();

            for (JsonNode term : root.path("terms")) {
                String fieldName = term.path("field_name").asText("");
                String value = term.path("value").asText("");
                String evidence = term.path("evidence").asText("");
                String sectionRef = term.has("section_ref") && !term.path("section_ref").isNull()
                        ? term.path("section_ref").asText() : null;

                if (fieldName.isBlank() || value.isBlank() || "null".equalsIgnoreCase(value)) continue;

                List<EvidenceSpan> spans = new ArrayList<>();
                if (!evidence.isBlank() && !"null".equalsIgnoreCase(evidence)) {
                    spans.add(new EvidenceSpan(evidence, -1, -1)); // Offsets computed later by EvidenceValidator
                }

                entries.add(new ExtractionEntry(
                        null, fieldName, value, null,
                        spans, null, null, null, null,
                        null, false, sectionRef
                ));
            }

            return entries;
        } catch (Exception e) {
            log.warn("Discovery LLM call failed (source={}): {}", source, e.getMessage());
            return List.of();
        }
    }

    /** Split text into overlapping windows for LLM processing */
    private List<String> chunkText(String text) {
        // Gemini can handle full documents — no chunking needed
        if ("gemini".equalsIgnoreCase(chatProvider) || text.length() <= windowSize) {
            return List.of(text.length() > 40000 ? text.substring(0, 40000) : text);
        }

        // vLLM/SaulLM: chunk with overlap
        List<String> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + windowSize, text.length());
            chunks.add(text.substring(pos, end));
            pos += windowSize - overlapChars;
            if (end >= text.length()) break;
        }
        return chunks;
    }

    /** Find the start of a clause section by keyword (reuses AiService pattern) */
    private int findClauseStart(String fullText, String[] keywords) {
        String lower = fullText.toLowerCase();
        int earliest = -1;
        for (String kw : keywords) {
            int idx = lower.indexOf(kw.toLowerCase());
            if (idx >= 0 && (earliest < 0 || idx < earliest)) earliest = idx;
        }
        return earliest;
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
