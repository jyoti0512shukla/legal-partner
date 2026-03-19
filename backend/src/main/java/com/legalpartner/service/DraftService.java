package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.LegalSystemConfig;
import com.legalpartner.model.dto.DraftRequest;
import com.legalpartner.model.dto.DraftResponse;
import com.legalpartner.model.dto.DraftResponse.ClauseSuggestion;
import com.legalpartner.rag.DraftContextRetriever;
import com.legalpartner.rag.DraftContextRetriever.DraftContext;
import com.legalpartner.rag.PromptTemplates;
import dev.langchain4j.data.message.AiMessage;
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
    private final LegalSystemConfig legalSystemConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DraftService(TemplateService templateService,
                        DraftContextRetriever draftContextRetriever,
                        ChatLanguageModel chatModel,
                        LegalSystemConfig legalSystemConfig,
                        @Value("${legalpartner.draft.max-concurrent:2}") int maxConcurrent) {
        this.templateService = templateService;
        this.draftContextRetriever = draftContextRetriever;
        this.chatModel = chatModel;
        this.legalSystemConfig = legalSystemConfig;
        this.draftSemaphore = new Semaphore(maxConcurrent);
    }

    private record ClauseSpec(String title, String systemPrompt, String userPromptTemplate, int expectedSubclauses) {}

    private static final Map<String, ClauseSpec> CLAUSE_SPECS = new LinkedHashMap<>();
    static {
        CLAUSE_SPECS.put("DEFINITIONS",               new ClauseSpec("Definitions",                    PromptTemplates.DRAFT_DEFINITIONS_SYSTEM,               PromptTemplates.DRAFT_DEFINITIONS_USER,               7));
        CLAUSE_SPECS.put("SERVICES",                  new ClauseSpec("Services",                       PromptTemplates.DRAFT_SERVICES_SYSTEM,                  PromptTemplates.DRAFT_SERVICES_USER,                  0));
        CLAUSE_SPECS.put("PAYMENT",                   new ClauseSpec("Fees and Payment",               PromptTemplates.DRAFT_PAYMENT_SYSTEM,                   PromptTemplates.DRAFT_PAYMENT_USER,                   0));
        CLAUSE_SPECS.put("CONFIDENTIALITY",           new ClauseSpec("Confidentiality",                PromptTemplates.DRAFT_CONFIDENTIALITY_SYSTEM,           PromptTemplates.DRAFT_CONFIDENTIALITY_USER,           5));
        CLAUSE_SPECS.put("IP_RIGHTS",                 new ClauseSpec("Intellectual Property Rights",   PromptTemplates.DRAFT_IP_RIGHTS_SYSTEM,                 PromptTemplates.DRAFT_IP_RIGHTS_USER,                 0));
        CLAUSE_SPECS.put("LIABILITY",                 new ClauseSpec("Liability and Indemnity",        PromptTemplates.DRAFT_LIABILITY_SYSTEM,                 PromptTemplates.DRAFT_LIABILITY_USER,                 5));
        CLAUSE_SPECS.put("TERMINATION",               new ClauseSpec("Termination",                    PromptTemplates.DRAFT_TERMINATION_SYSTEM,               PromptTemplates.DRAFT_TERMINATION_USER,               4));
        CLAUSE_SPECS.put("FORCE_MAJEURE",             new ClauseSpec("Force Majeure",                  PromptTemplates.DRAFT_FORCE_MAJEURE_SYSTEM,             PromptTemplates.DRAFT_FORCE_MAJEURE_USER,             5));
        CLAUSE_SPECS.put("REPRESENTATIONS_WARRANTIES",new ClauseSpec("Representations and Warranties", PromptTemplates.DRAFT_REPRESENTATIONS_WARRANTIES_SYSTEM, PromptTemplates.DRAFT_REPRESENTATIONS_WARRANTIES_USER, 5));
        CLAUSE_SPECS.put("DATA_PROTECTION",           new ClauseSpec("Data Protection and Privacy",    PromptTemplates.DRAFT_DATA_PROTECTION_SYSTEM,           PromptTemplates.DRAFT_DATA_PROTECTION_USER,           5));
        CLAUSE_SPECS.put("GOVERNING_LAW",             new ClauseSpec("Governing Law and Dispute Resolution", PromptTemplates.DRAFT_GOVERNING_LAW_SYSTEM,       PromptTemplates.DRAFT_GOVERNING_LAW_USER,             4));
        CLAUSE_SPECS.put("GENERAL_PROVISIONS",        new ClauseSpec("General Provisions",             PromptTemplates.DRAFT_GENERAL_PROVISIONS_SYSTEM,        PromptTemplates.DRAFT_GENERAL_PROVISIONS_USER,        8));
    }

    // ── QA: placeholder detection pattern ─────────────────────────────────────
    private static final java.util.regex.Pattern QA_PLACEHOLDER_PATTERN =
            java.util.regex.Pattern.compile(
                    "\\[(?!\\d)[^\\]]{2,60}\\]"          // [some placeholder text]
                    + "|(\\(insert[^)]{0,50}\\))"         // (insert ...)
                    + "|%[A-Z][A-Z_]{2,}%"                // %LEFTOVER_MARKER%
                    + "|\\bTBC\\b|\\bTBD\\b",             // TBC / TBD
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );

    // ── Public API ─────────────────────────────────────────────────────────────

    public DraftResponse generateDraft(DraftRequest request, String username) {
        if (!draftSemaphore.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Draft generation is busy. Please try again in a moment.");
        }
        try {
            return doGenerateDraft(request);
        } finally {
            draftSemaphore.release();
        }
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

    // ── Core generation ────────────────────────────────────────────────────────

    private DraftResponse doGenerateDraft(DraftRequest request) {
        String[] templateParts = loadTemplateParts(request.getTemplateId(), buildPlaceholderMap(request));

        List<String> plannedSections = planSections(request);
        log.info("Section planner (sync) chose {} sections: {}", plannedSections.size(), plannedSections);

        Map<String, String> sectionValues = new LinkedHashMap<>();
        List<ClauseSuggestion> suggestions = new ArrayList<>();
        Map<String, List<String>> allQaWarnings = new LinkedHashMap<>();

        for (String key : plannedSections) {
            ClauseSpec spec = CLAUSE_SPECS.get(key);
            DraftContext ctx = draftContextRetriever.retrieveForClause(key, request);
            String generated = generateClause(request, ctx, spec.systemPrompt(), spec.userPromptTemplate());
            sectionValues.put(key, generated);

            List<String> qaWarnings = qaClause(key, generated, spec.expectedSubclauses());
            if (!qaWarnings.isEmpty()) allQaWarnings.put(key, qaWarnings);

            String reasoning = "Generated using RAG from firm's corpus.";
            if (!ctx.sourceDocuments().isEmpty()) {
                reasoning += " Sources: " + String.join(", ", ctx.sourceDocuments().subList(0, Math.min(3, ctx.sourceDocuments().size())));
            }
            suggestions.add(ClauseSuggestion.builder()
                    .clauseRef(spec.title() + " clause (AI-generated)")
                    .currentText("(AI-generated)")
                    .suggestion("Review and customize for your specific matter and client.")
                    .reasoning(reasoning)
                    .build());
        }

        return DraftResponse.builder()
                .draftHtml(buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues))
                .suggestions(suggestions)
                .qaWarnings(allQaWarnings.isEmpty() ? null : allQaWarnings)
                .build();
    }

    private void doStreamDraft(DraftRequest request, SseEmitter emitter) throws Exception {
        String[] templateParts = loadTemplateParts(request.getTemplateId(), buildPlaceholderMap(request));

        // Phase 1: plan sections
        emitter.send(SseEmitter.event().data(toJson(Map.of("type", "planning"))));

        List<String> plannedSections = planSections(request);
        log.info("Section planner chose {} sections: {}", plannedSections.size(), plannedSections);

        // Initialise all section slots with "Generating…" placeholders
        Map<String, String> sectionValues = new LinkedHashMap<>();
        for (String key : plannedSections) {
            ClauseSpec spec = CLAUSE_SPECS.get(key);
            sectionValues.put(key, "<em style='color:#9CA3AF'>&#x23F3; Generating " + spec.title() + " clause…</em>");
        }

        emitter.send(SseEmitter.event().data(toJson(Map.of(
                "type", "start",
                "totalClauses", plannedSections.size(),
                "plannedSections", plannedSections,
                "partialHtml", buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues)))));

        List<ClauseSuggestion> suggestions = new ArrayList<>();
        Map<String, List<String>> allQaWarnings = new LinkedHashMap<>();

        // Phase 2: generate each planned section
        for (int i = 0; i < plannedSections.size(); i++) {
            String key = plannedSections.get(i);
            ClauseSpec spec = CLAUSE_SPECS.get(key);

            emitter.send(SseEmitter.event().data(toJson(Map.of(
                    "type", "clause_start",
                    "clauseType", key,
                    "label", spec.title(),
                    "index", i + 1,
                    "totalClauses", plannedSections.size()))));

            DraftContext ctx = draftContextRetriever.retrieveForClause(key, request);
            String generated = generateClause(request, ctx, spec.systemPrompt(), spec.userPromptTemplate());
            sectionValues.put(key, generated);

            // QA pass: check for placeholders and completeness
            List<String> qaWarnings = qaClause(key, generated, spec.expectedSubclauses());
            if (!qaWarnings.isEmpty()) allQaWarnings.put(key, qaWarnings);

            Map<String, Object> clauseDonePayload = new LinkedHashMap<>();
            clauseDonePayload.put("type", "clause_done");
            clauseDonePayload.put("clauseType", key);
            clauseDonePayload.put("label", spec.title());
            clauseDonePayload.put("index", i + 1);
            clauseDonePayload.put("totalClauses", plannedSections.size());
            clauseDonePayload.put("qaWarnings", qaWarnings);
            clauseDonePayload.put("partialHtml", buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues));
            emitter.send(SseEmitter.event().data(toJson(clauseDonePayload)));

            String reasoning = "Generated using RAG from firm's corpus.";
            if (!ctx.sourceDocuments().isEmpty()) {
                reasoning += " Sources: " + String.join(", ", ctx.sourceDocuments().subList(0, Math.min(3, ctx.sourceDocuments().size())));
            }
            suggestions.add(ClauseSuggestion.builder()
                    .clauseRef(spec.title() + " clause (AI-generated)")
                    .currentText("(AI-generated)")
                    .suggestion("Review and customize for your specific matter and client.")
                    .reasoning(reasoning)
                    .build());
        }

        Map<String, Object> completePayload = new LinkedHashMap<>();
        completePayload.put("type", "complete");
        completePayload.put("draftHtml", buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues));
        completePayload.put("suggestions", suggestions);
        completePayload.put("qaWarnings", allQaWarnings);
        emitter.send(SseEmitter.event().data(toJson(completePayload)));
        emitter.complete();
    }

    // ── Section planner ────────────────────────────────────────────────────────

    /**
     * Ask the LLM which sections this contract should contain, in order.
     * Returns a list of keys from CLAUSE_SPECS. Falls back to defaults if parsing fails.
     */
    private List<String> planSections(DraftRequest request) {
        String prompt = String.format(PromptTemplates.SECTION_PLANNER_USER,
                nullToDefault(request.getTemplateId(), "contract").toUpperCase(),
                nullToDefault(request.getPartyA(), "Party A"),
                nullToDefault(request.getPartyB(), "Party B"),
                nullToDefault(request.getPracticeArea(), "general"),
                nullToDefault(request.getIndustry(), "GENERAL"),
                nullToDefault(request.getDealBrief(), "standard contract"));

        try {
            AiMessage response = chatModel.generate(
                    UserMessage.from(legalSystemConfig.localize(PromptTemplates.SECTION_PLANNER_SYSTEM) + "\n\n" + prompt)
            ).content();

            String text = response.text().trim();
            // Extract JSON array from response (model may add surrounding text)
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) {
                text = text.substring(start, end + 1);
            }
            List<String> parsed = objectMapper.readValue(text, new TypeReference<List<String>>() {});
            List<String> known = parsed.stream()
                    .filter(CLAUSE_SPECS::containsKey)
                    .collect(Collectors.toList());
            if (!known.isEmpty()) {
                log.info("Section planner returned: {}", known);
                return known;
            }
            log.warn("Section planner returned no known sections; using defaults");
        } catch (Exception e) {
            log.warn("Section planner failed ({}), using defaults", e.getMessage());
        }
        return defaultSections(request.getTemplateId());
    }

    private List<String> defaultSections(String templateId) {
        if ("nda".equalsIgnoreCase(templateId)) {
            return List.of("DEFINITIONS", "CONFIDENTIALITY", "LIABILITY", "TERMINATION", "GOVERNING_LAW", "GENERAL_PROVISIONS");
        }
        // MSA and everything else
        return List.of("DEFINITIONS", "SERVICES", "PAYMENT", "CONFIDENTIALITY", "IP_RIGHTS",
                "LIABILITY", "TERMINATION", "GOVERNING_LAW", "GENERAL_PROVISIONS");
    }

    // ── HTML assembly ──────────────────────────────────────────────────────────

    /**
     * Load template and split it into [metadataHtml, footerHtml] around the {{AI_SECTIONS}} marker.
     * The metadataHtml already has all {{PARTY_A}} etc. replaced.
     */
    private String[] loadTemplateParts(String templateId, Map<String, String> values) {
        String raw = templateService.loadTemplate(templateId);
        String filled = replacePlaceholders(raw, values);
        int marker = filled.indexOf("{{AI_SECTIONS}}");
        if (marker >= 0) {
            return new String[]{
                filled.substring(0, marker),
                filled.substring(marker + "{{AI_SECTIONS}}".length())
            };
        }
        // Legacy template without marker — put sections before </body>
        int bodyClose = filled.lastIndexOf("</body>");
        if (bodyClose >= 0) {
            return new String[]{ filled.substring(0, bodyClose), filled.substring(bodyClose) };
        }
        return new String[]{ filled, "" };
    }

    private String buildDynamicHtml(String metadataHtml, String footerHtml,
                                    List<String> sections, Map<String, String> sectionValues) {
        StringBuilder sb = new StringBuilder(metadataHtml);
        for (int i = 0; i < sections.size(); i++) {
            String key = sections.get(i);
            ClauseSpec spec = CLAUSE_SPECS.get(key);
            String content = sectionValues.getOrDefault(key, "");
            sb.append("\n<h2>ARTICLE ").append(i + 1)
              .append(" \u2014 ").append(spec.title().toUpperCase()).append("</h2>\n");
            sb.append("<div class=\"article-body\">").append(content).append("</div>\n");
        }
        sb.append(footerHtml);
        return sb.toString();
    }

    // ── Clause generation ──────────────────────────────────────────────────────

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

        // Append content guardrails to every draft system prompt to prevent placeholder leakage
        String localizedSystemPrompt = legalSystemConfig.localize(systemPrompt) + PromptTemplates.DRAFT_CONTENT_GUARDRAILS;

        AiMessage response = chatModel.generate(
                UserMessage.from(localizedSystemPrompt + "\n\n" + prompt)
        ).content();

        return sanitizeClauseText(response.text().trim());
    }

    /**
     * Post-generation QA pass. Detects unfilled placeholders and incomplete sub-clause counts.
     * Returns a list of human-readable warning strings (empty = clean).
     */
    private List<String> qaClause(String clauseKey, String htmlText, int expectedSubclauses) {
        List<String> warnings = new ArrayList<>();

        // Strip HTML tags for plain-text analysis
        String plain = htmlText.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();

        // 1. Placeholder detection
        java.util.regex.Matcher m = QA_PLACEHOLDER_PATTERN.matcher(plain);
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        while (m.find()) found.add(m.group());
        for (String ph : found) {
            warnings.add("Unfilled placeholder: " + ph);
            log.warn("QA [{}]: unfilled placeholder detected — {}", clauseKey, ph);
        }

        // 2. Sub-clause count check (only for clauses with a strict expected count)
        if (expectedSubclauses > 0) {
            int actual = 0;
            int idx = 0;
            String marker = "clause-sub";
            while ((idx = htmlText.indexOf(marker, idx)) >= 0) { actual++; idx += marker.length(); }
            if (actual < expectedSubclauses) {
                String msg = "Incomplete: expected " + expectedSubclauses + " sub-clauses, found " + actual;
                warnings.add(msg);
                log.warn("QA [{}]: {}", clauseKey, msg);
            }
        }

        return warnings;
    }

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

    // ── Clause text sanitizer ──────────────────────────────────────────────────

    private static final java.util.regex.Pattern DEGENERATE_LINE =
            java.util.regex.Pattern.compile("(\\d)\\1{9,}|(-n){5,}|(n-){5,}|([a-zA-Z0-9])\\4{15,}");
    private static final java.util.regex.Pattern CLAUSE_NUMBER =
            java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)[.)\\s]");
    private static final java.util.regex.Pattern INLINE_CLAUSE_SPLIT =
            java.util.regex.Pattern.compile("(?<=[.!?)])\\s+(?=\\d{1,3}[.)\\s])");

    private String sanitizeClauseText(String text) {
        String cleaned = text
                .replaceFirst("(?i)^\\s*Response\\s*:\\s*", "")
                .replaceFirst("(?i)^\\s*[A-Za-z][A-Za-z ]{2,39}\\s+Clause\\s*:?\\s*", "")
                .trim();

        String[] rawLines = cleaned.split("\\r?\\n");
        List<String> lines = new java.util.ArrayList<>();
        for (String rawLine : rawLines) {
            String[] parts = INLINE_CLAUSE_SPLIT.split(rawLine);
            for (String part : parts) {
                if (!part.isBlank()) lines.add(part.trim());
            }
        }

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

        if (!kept.isEmpty()) {
            String last = kept.get(kept.size() - 1);
            int lastEnd = -1;
            for (int i = last.length() - 1; i >= 0; i--) {
                char c = last.charAt(i);
                if (c == '.' || c == '?' || c == '!' || c == ')' || c == ']') { lastEnd = i; break; }
            }
            if (lastEnd > 0 && lastEnd < last.length() - 1) {
                kept.set(kept.size() - 1, last.substring(0, lastEnd + 1));
            }
        }

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

    // ── Helpers ────────────────────────────────────────────────────────────────

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
