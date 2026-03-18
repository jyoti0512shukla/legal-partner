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

    private String generateClause(DraftRequest request, DraftContext ctx,
                                   String systemPrompt, String userPromptTemplate) {
        String contractType = request.getTemplateId() != null ? request.getTemplateId().toUpperCase() : "CONTRACT";
        String jurisdiction = nullToDefault(request.getJurisdiction(), "India");
        String counterparty = nullToDefault(request.getCounterpartyType(), "general");
        String practiceArea = nullToDefault(request.getPracticeArea(), "general");

        String prompt = String.format(userPromptTemplate, contractType, jurisdiction, counterparty, practiceArea, ctx.structuredContext());

        if (ctx.chunkCount() > 0) {
            log.info("Draft context: {} chunks from {} sources", ctx.chunkCount(), ctx.sourceDocuments().size());
        }

        AiMessage response = chatModel.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(prompt)
        ).content();

        return response.text().trim();
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
