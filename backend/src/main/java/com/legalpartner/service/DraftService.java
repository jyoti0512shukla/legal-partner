package com.legalpartner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.DraftRequest;
import com.legalpartner.model.dto.DraftResponse;
import com.legalpartner.model.dto.DraftResponse.ClauseSuggestion;
import com.legalpartner.rag.DraftContextRetriever;
import com.legalpartner.rag.DraftContextRetriever.DraftContext;
import com.legalpartner.rag.PromptTemplates;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DraftService {

    private final TemplateService templateService;
    private final DraftContextRetriever draftContextRetriever;
    private final ChatLanguageModel chatModel;
    private final Semaphore draftSemaphore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DraftService(TemplateService templateService,
                        DraftContextRetriever draftContextRetriever,
                        ChatLanguageModel chatModel,
                        @Value("${legalpartner.draft.max-concurrent:2}") int maxConcurrent) {
        this.templateService = templateService;
        this.draftContextRetriever = draftContextRetriever;
        this.chatModel = chatModel;
        this.draftSemaphore = new Semaphore(maxConcurrent);
    }

    private static final Map<String, String[]> CLAUSE_PLACEHOLDER_TO_TYPE = Map.of(
        "LIABILITY_CLAUSE", new String[]{"LIABILITY", PromptTemplates.DRAFT_LIABILITY_SYSTEM, PromptTemplates.DRAFT_LIABILITY_USER},
        "TERMINATION_CLAUSE", new String[]{"TERMINATION", PromptTemplates.DRAFT_TERMINATION_SYSTEM, PromptTemplates.DRAFT_TERMINATION_USER},
        "CONFIDENTIALITY_CLAUSE", new String[]{"CONFIDENTIALITY", PromptTemplates.DRAFT_CONFIDENTIALITY_SYSTEM, PromptTemplates.DRAFT_CONFIDENTIALITY_USER},
        "GOVERNING_LAW_CLAUSE", new String[]{"GOVERNING_LAW", PromptTemplates.DRAFT_GOVERNING_LAW_SYSTEM, PromptTemplates.DRAFT_GOVERNING_LAW_USER},
        "IP_RIGHTS_CLAUSE", new String[]{"IP_RIGHTS", PromptTemplates.DRAFT_IP_RIGHTS_SYSTEM, PromptTemplates.DRAFT_IP_RIGHTS_USER},
        "PAYMENT_CLAUSE", new String[]{"PAYMENT", PromptTemplates.DRAFT_PAYMENT_SYSTEM, PromptTemplates.DRAFT_PAYMENT_USER},
        "SERVICES_CLAUSE", new String[]{"SERVICES", PromptTemplates.DRAFT_SERVICES_SYSTEM, PromptTemplates.DRAFT_SERVICES_USER},
        "DEFINITIONS_CLAUSE", new String[]{"DEFINITIONS", PromptTemplates.DRAFT_DEFINITIONS_SYSTEM, PromptTemplates.DRAFT_DEFINITIONS_USER},
        "GENERAL_PROVISIONS_CLAUSE", new String[]{"GENERAL_PROVISIONS", PromptTemplates.DRAFT_GENERAL_PROVISIONS_SYSTEM, PromptTemplates.DRAFT_GENERAL_PROVISIONS_USER}
    );

    public DraftResponse generateDraft(DraftRequest request, String username) {
        if (!draftSemaphore.tryAcquire()) {
            log.warn("Draft rate limit hit — {} permits in use", draftSemaphore.availablePermits());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Draft generation is busy. Please try again in a moment.");
        }
        try {
            return doGenerateDraft(request);
        } finally {
            draftSemaphore.release();
        }
    }

    private DraftResponse doGenerateDraft(DraftRequest request) {
        String template = templateService.loadTemplate(request.getTemplateId());
        Map<String, String> values = buildPlaceholderMap(request);
        String filled = replacePlaceholders(template, values);

        List<ClauseSuggestion> suggestions = new ArrayList<>();

        for (Map.Entry<String, String[]> entry : CLAUSE_PLACEHOLDER_TO_TYPE.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            if (!filled.contains(placeholder)) continue;

            String clauseType = entry.getValue()[0];
            String systemPrompt = entry.getValue()[1];
            String userPromptTemplate = entry.getValue()[2];

            DraftContext ctx = draftContextRetriever.retrieveForClause(clauseType, request);
            String generatedClause = generateClause(request, ctx, systemPrompt, userPromptTemplate);
            filled = filled.replace(placeholder, generatedClause);

            String reasoning = "Generated using RAG from firm's corpus (filtered by contract type, jurisdiction).";
            if (!ctx.sourceDocuments().isEmpty()) {
                reasoning += " Sources: " + String.join(", ", ctx.sourceDocuments().subList(0, Math.min(3, ctx.sourceDocuments().size())));
            }
            suggestions.add(ClauseSuggestion.builder()
                    .clauseRef(clauseType + " clause (AI-generated)")
                    .currentText("(AI-generated)")
                    .suggestion("Review and customize for your specific matter and client.")
                    .reasoning(reasoning)
                    .build());
        }

        return DraftResponse.builder()
                .draftHtml(filled)
                .suggestions(suggestions)
                .build();
    }

    /**
     * Build the deal context block that is injected into every clause prompt.
     * Empty string when no context fields are set (backward-compatible).
     */
    private String buildDealContext(DraftRequest request) {
        StringBuilder sb = new StringBuilder();

        String brief = request.getDealBrief();
        if (brief != null && !brief.isBlank()) {
            sb.append("\nDeal brief: ").append(brief.strip()).append("\n");
        }

        String position = request.getClientPosition();
        if (position != null && !position.isBlank()) {
            String posLabel = switch (position.toUpperCase()) {
                case "PARTY_A" -> "Firm represents Party A — draft clauses favourably for Party A.";
                case "PARTY_B" -> "Firm represents Party B — draft clauses favourably for Party B.";
                default -> "Firm is acting as neutral drafter — balanced terms preferred.";
            };
            sb.append("Client position: ").append(posLabel).append("\n");
        }

        String industry = request.getIndustry();
        if (industry != null && !industry.isBlank()) {
            String regRef = switch (industry.toUpperCase()) {
                case "FINTECH"        -> "Reference RBI guidelines, FEMA 1999, and Payment & Settlement Systems Act 2007 where relevant.";
                case "PHARMA"        -> "Reference Drugs and Cosmetics Act 1940, Clinical Establishment Act 2010, and DPDPA 2023 where relevant.";
                case "IT_SERVICES"   -> "Reference IT Act 2000, DPDPA 2023, and SEBI (if listed entity) where relevant.";
                case "MANUFACTURING" -> "Reference Factories Act 1948, Environment Protection Act 1986, and GST Act 2017 where relevant.";
                default -> "";
            };
            if (!regRef.isEmpty()) {
                sb.append("Industry: ").append(industry).append(". ").append(regRef).append("\n");
            }
        }

        String stance = request.getDraftStance();
        if (stance != null && !stance.isBlank()) {
            String stanceLabel = switch (stance.toUpperCase()) {
                case "FIRST_DRAFT" -> "This is a first draft — maximise protections for the client; include strong caps, broad indemnity carve-outs, liberal termination rights.";
                case "FINAL_OFFER" -> "This is a final offer — use firm but commercially reasonable language; avoid overreaching terms that may cause deadlock.";
                default -> "Use balanced, commercially standard terms acceptable to both parties.";
            };
            sb.append("Drafting stance: ").append(stanceLabel).append("\n");
        }

        return sb.length() == 0 ? "" : sb.toString();
    }

    private String generateClause(DraftRequest request, DraftContext ctx,
                                   String systemPrompt, String userPromptTemplate) {
        String contractType = request.getTemplateId() != null ? request.getTemplateId().toUpperCase() : "CONTRACT";
        String jurisdiction = nullToDefault(request.getJurisdiction(), "India");
        String counterparty = nullToDefault(request.getCounterpartyType(), "general");
        String practiceArea = nullToDefault(request.getPracticeArea(), "general");
        String dealContext = buildDealContext(request);

        String prompt = String.format(userPromptTemplate, contractType, jurisdiction, counterparty, practiceArea, dealContext, ctx.structuredContext());

        if (ctx.chunkCount() > 0) {
            log.info("Draft context: {} chunks from {} sources", ctx.chunkCount(), ctx.sourceDocuments().size());
        }

        AiMessage response = chatModel.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(prompt)
        ).content();

        return sanitizeClauseText(response.text().trim());
    }

    /**
     * Clean and format LLM clause output for HTML insertion:
     * 1. Strip "Response:" / "Xxx Clause:" prefix echoes
     * 2. Split inline numbered clauses onto separate lines (model often emits them as one long line)
     * 3. Truncate on character-level repetition (111..., n-n-n...)
     * 4. Truncate on semantic repetition (same numbered clause body appearing twice)
     * 5. Trim trailing incomplete sentence
     * 6. Wrap each numbered sub-clause in <p> for proper HTML rendering
     */
    private static final java.util.regex.Pattern DEGENERATE_LINE =
            java.util.regex.Pattern.compile("(\\d)\\1{9,}|(-n){5,}|(n-){5,}|([a-zA-Z0-9])\\4{15,}");

    // Matches "N." or "N.N" at start of a clause segment
    private static final java.util.regex.Pattern CLAUSE_NUMBER =
            java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)[.)\\s]");

    // Splits inline "... text. 2. Next clause..." into separate segments
    // Looks for: space + number + dot/paren + space (not inside a year like 1872)
    private static final java.util.regex.Pattern INLINE_CLAUSE_SPLIT =
            java.util.regex.Pattern.compile("(?<=[.!?)])\\s+(?=\\d{1,3}[.)\\s])");

    private String sanitizeClauseText(String text) {
        // Strip prefix echoes: "Response:", "Confidentiality Clause:", "Definitions Clause" etc.
        String cleaned = text
                .replaceFirst("(?i)^\\s*Response\\s*:\\s*", "")
                .replaceFirst("(?i)^\\s*[A-Za-z][A-Za-z ]{2,39}\\s+Clause\\s*:?\\s*", "")
                .trim();

        // Split inline clauses onto their own lines
        // Model often emits: "1. Entire Agreement: ... 2. Amendments: ... 3. ..."
        String[] rawLines = cleaned.split("\\r?\\n");
        List<String> lines = new java.util.ArrayList<>();
        for (String rawLine : rawLines) {
            // If a line contains embedded inline numbered clauses, split it
            String[] parts = INLINE_CLAUSE_SPLIT.split(rawLine);
            for (String part : parts) {
                if (!part.isBlank()) lines.add(part.trim());
            }
        }

        // Pass 1 — char-level degenerate + semantic dedup
        java.util.Set<String> seenBodies = new java.util.LinkedHashSet<>();
        List<String> kept = new java.util.ArrayList<>();

        for (String line : lines) {
            if (DEGENERATE_LINE.matcher(line).find()) {
                log.warn("Draft sanitizer: char-degenerate truncation at: {}", line.substring(0, Math.min(60, line.length())));
                break;
            }
            java.util.regex.Matcher m = CLAUSE_NUMBER.matcher(line);
            if (m.find()) {
                String body = line.substring(m.end()).trim().toLowerCase();
                String fingerprint = body.substring(0, Math.min(80, body.length()));
                if (!fingerprint.isBlank() && !seenBodies.add(fingerprint)) {
                    log.warn("Draft sanitizer: semantic-loop truncation at duplicate: {}", line.substring(0, Math.min(80, line.length())));
                    break;
                }
            }
            kept.add(line);
        }

        // Pass 2 — trim trailing incomplete sentence on the last kept line
        if (!kept.isEmpty()) {
            String last = kept.get(kept.size() - 1);
            int lastEnd = -1;
            for (int i = last.length() - 1; i >= 0; i--) {
                char c = last.charAt(i);
                if (c == '.' || c == '?' || c == '!' || c == ')' || c == ']') {
                    lastEnd = i;
                    break;
                }
            }
            if (lastEnd > 0 && lastEnd < last.length() - 1) {
                kept.set(kept.size() - 1, last.substring(0, lastEnd + 1));
            }
        }

        // Pass 3 — render as HTML paragraphs
        // Numbered clause lines → <p class="clause-sub">...; plain lines → <p>
        StringBuilder html = new StringBuilder();
        for (String line : kept) {
            if (line.isBlank()) continue;
            java.util.regex.Matcher m = CLAUSE_NUMBER.matcher(line);
            if (m.find()) {
                String num = m.group(1);
                String body = line.substring(m.end()).trim();
                html.append("<p class=\"clause-sub\"><strong>").append(num).append(".</strong> ").append(body).append("</p>\n");
            } else {
                html.append("<p>").append(line).append("</p>\n");
            }
        }

        return html.toString().stripTrailing();
    }

    public SseEmitter streamDraft(DraftRequest request, String username) {
        SseEmitter emitter = new SseEmitter(300_000L);
        if (!draftSemaphore.tryAcquire()) {
            try {
                emitter.send(SseEmitter.event().data(toJson(Map.of("type", "error", "message", "Draft generation is busy. Please try again."))));
                emitter.complete();
            } catch (IOException e) { emitter.completeWithError(e); }
            return emitter;
        }
        new Thread(() -> {
            try { doStreamDraft(request, emitter); }
            catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data(toJson(Map.of("type", "error", "message", e.getMessage() != null ? e.getMessage() : "Generation failed"))));
                    emitter.complete();
                } catch (IOException ignored) { emitter.completeWithError(e); }
            } finally { draftSemaphore.release(); }
        }).start();
        return emitter;
    }

    private void doStreamDraft(DraftRequest request, SseEmitter emitter) throws Exception {
        String template = templateService.loadTemplate(request.getTemplateId());
        Map<String, String> values = buildPlaceholderMap(request);
        String filled = replacePlaceholders(template, values);

        // Find placeholders present in the template, ordered by their position
        List<Map.Entry<String, String[]>> orderedClauses = CLAUSE_PLACEHOLDER_TO_TYPE.entrySet().stream()
                .filter(e -> filled.contains("{{" + e.getKey() + "}}"))
                .sorted(Comparator.comparingInt(e -> filled.indexOf("{{" + e.getKey() + "}}")))
                .collect(Collectors.toList());

        // Initialise all placeholders with "Generating…" spans
        Map<String, String> clauseValues = new LinkedHashMap<>();
        for (var entry : orderedClauses) {
            String label = clauseLabel(entry.getValue()[0]);
            clauseValues.put("{{" + entry.getKey() + "}}",
                    "<em style='color:#9CA3AF'>&#x23F3; Generating " + label + " clause…</em>");
        }

        emitter.send(SseEmitter.event().data(toJson(Map.of(
                "type", "start",
                "totalClauses", orderedClauses.size(),
                "partialHtml", buildPartialHtml(filled, clauseValues)))));

        List<ClauseSuggestion> suggestions = new ArrayList<>();

        for (int i = 0; i < orderedClauses.size(); i++) {
            var entry = orderedClauses.get(i);
            String placeholder = "{{" + entry.getKey() + "}}";
            String clauseType = entry.getValue()[0];
            String label = clauseLabel(clauseType);

            emitter.send(SseEmitter.event().data(toJson(Map.of(
                    "type", "clause_start",
                    "clauseType", clauseType,
                    "label", label,
                    "index", i + 1,
                    "totalClauses", orderedClauses.size()))));

            DraftContext ctx = draftContextRetriever.retrieveForClause(clauseType, request);
            String generatedClause = generateClause(request, ctx, entry.getValue()[1], entry.getValue()[2]);

            clauseValues.put(placeholder, generatedClause);

            emitter.send(SseEmitter.event().data(toJson(Map.of(
                    "type", "clause_done",
                    "clauseType", clauseType,
                    "label", label,
                    "index", i + 1,
                    "totalClauses", orderedClauses.size(),
                    "partialHtml", buildPartialHtml(filled, clauseValues)))));

            String reasoning = "Generated using RAG from firm's corpus (filtered by contract type, jurisdiction).";
            if (!ctx.sourceDocuments().isEmpty()) {
                reasoning += " Sources: " + String.join(", ", ctx.sourceDocuments().subList(0, Math.min(3, ctx.sourceDocuments().size())));
            }
            suggestions.add(ClauseSuggestion.builder()
                    .clauseRef(label + " clause (AI-generated)")
                    .currentText("(AI-generated)")
                    .suggestion("Review and customize for your specific matter and client.")
                    .reasoning(reasoning)
                    .build());
        }

        emitter.send(SseEmitter.event().data(toJson(Map.of(
                "type", "complete",
                "draftHtml", buildPartialHtml(filled, clauseValues),
                "suggestions", suggestions))));
        emitter.complete();
    }

    private String buildPartialHtml(String template, Map<String, String> clauseValues) {
        String result = template;
        for (var e : clauseValues.entrySet()) result = result.replace(e.getKey(), e.getValue());
        return result;
    }

    private String clauseLabel(String clauseType) {
        return Arrays.stream(clauseType.split("_"))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private Map<String, String> buildPlaceholderMap(DraftRequest r) {
        Map<String, String> m = new HashMap<>();
        m.put("PARTY_A", nullToDefault(r.getPartyA(), "Party A"));
        m.put("PARTY_B", nullToDefault(r.getPartyB(), "Party B"));
        m.put("PARTY_A_ADDRESS", nullToDefault(r.getPartyAAddress(), "[Address]"));
        m.put("PARTY_B_ADDRESS", nullToDefault(r.getPartyBAddress(), "[Address]"));
        m.put("PARTY_A_REP", nullToDefault(r.getPartyARep(), "[Representative]"));
        m.put("PARTY_B_REP", nullToDefault(r.getPartyBRep(), "[Representative]"));
        m.put("EFFECTIVE_DATE", nullToDefault(r.getEffectiveDate(), "[Date]"));
        m.put("JURISDICTION", nullToDefault(r.getJurisdiction(), "India"));
        m.put("AGREEMENT_REF", nullToDefault(r.getAgreementRef(), "REF-001"));
        m.put("TERM_YEARS", nullToDefault(r.getTermYears(), "3"));
        m.put("NOTICE_DAYS", nullToDefault(r.getNoticeDays(), "30"));
        m.put("SURVIVAL_YEARS", nullToDefault(r.getSurvivalYears(), "5"));
        return m;
    }

    private String replacePlaceholders(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    private static String nullToDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
