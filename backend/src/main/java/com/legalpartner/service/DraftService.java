package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.LegalSystemConfig;
import com.legalpartner.model.dto.DraftRequest;
import com.legalpartner.model.dto.DraftResponse;
import com.legalpartner.model.dto.DraftResponse.ClauseSuggestion;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.Matter;
import com.legalpartner.model.enums.ProcessingStatus;
import com.legalpartner.rag.DraftContextRetriever;
import com.legalpartner.rag.DraftContextRetriever.DraftContext;
import com.legalpartner.rag.PromptTemplates;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.MatterRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final MatterRepository matterRepository;
    private final DocumentMetadataRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DraftService(TemplateService templateService,
                        DraftContextRetriever draftContextRetriever,
                        ChatLanguageModel chatModel,
                        LegalSystemConfig legalSystemConfig,
                        MatterRepository matterRepository,
                        DocumentMetadataRepository documentRepository,
                        FileStorageService fileStorageService,
                        @Value("${legalpartner.draft.max-concurrent:2}") int maxConcurrent) {
        this.templateService = templateService;
        this.draftContextRetriever = draftContextRetriever;
        this.chatModel = chatModel;
        this.legalSystemConfig = legalSystemConfig;
        this.matterRepository = matterRepository;
        this.documentRepository = documentRepository;
        this.fileStorageService = fileStorageService;
        this.draftSemaphore = new Semaphore(maxConcurrent);
    }

    /**
     * If matterId is provided, hydrate missing request fields from the matter.
     * Existing fields take precedence — only blanks get filled.
     */
    private void hydrateFromMatter(DraftRequest request) {
        if (request.getMatterId() == null || request.getMatterId().isBlank()) return;
        try {
            UUID matterUuid = UUID.fromString(request.getMatterId());
            Matter matter = matterRepository.findById(matterUuid).orElse(null);
            if (matter == null) {
                log.warn("Draft hydrate: matter {} not found", request.getMatterId());
                return;
            }
            // Pre-fill only blank fields
            if (isBlank(request.getPartyA()) && matter.getClientName() != null) {
                request.setPartyA(matter.getClientName());
            }
            if (isBlank(request.getPracticeArea()) && matter.getPracticeArea() != null) {
                request.setPracticeArea(matter.getPracticeArea().name());
            }
            // Append matter name to deal brief if not already mentioned
            String existingBrief = request.getDealBrief() != null ? request.getDealBrief() : "";
            if (!existingBrief.contains(matter.getName())) {
                String matterContext = "Matter: " + matter.getName() + " (" + matter.getMatterRef() + ")";
                if (matter.getDealType() != null) {
                    matterContext += ", Deal Type: " + matter.getDealType();
                }
                request.setDealBrief(matterContext + (existingBrief.isBlank() ? "" : ". " + existingBrief));
            }
            log.info("Draft hydrated from matter {}: client={}, practice={}",
                    matter.getMatterRef(), matter.getClientName(), matter.getPracticeArea());
        } catch (IllegalArgumentException e) {
            log.warn("Draft hydrate: invalid matterId format {}", request.getMatterId());
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private record ClauseSpec(String title, String systemPrompt, String userPromptTemplate, int expectedSubclauses) {}

    private static final Map<String, ClauseSpec> CLAUSE_SPECS = new LinkedHashMap<>();
    static {
        CLAUSE_SPECS.put("DEFINITIONS",               new ClauseSpec("Definitions",                    PromptTemplates.DRAFT_DEFINITIONS_SYSTEM,               PromptTemplates.DRAFT_DEFINITIONS_USER,               7));
        CLAUSE_SPECS.put("SERVICES",                  new ClauseSpec("Services",                       PromptTemplates.DRAFT_SERVICES_SYSTEM,                  PromptTemplates.DRAFT_SERVICES_USER,                  3));
        CLAUSE_SPECS.put("PAYMENT",                   new ClauseSpec("Fees and Payment",               PromptTemplates.DRAFT_PAYMENT_SYSTEM,                   PromptTemplates.DRAFT_PAYMENT_USER,                   4));
        CLAUSE_SPECS.put("CONFIDENTIALITY",           new ClauseSpec("Confidentiality",                PromptTemplates.DRAFT_CONFIDENTIALITY_SYSTEM,           PromptTemplates.DRAFT_CONFIDENTIALITY_USER,           5));
        CLAUSE_SPECS.put("IP_RIGHTS",                 new ClauseSpec("Intellectual Property Rights",   PromptTemplates.DRAFT_IP_RIGHTS_SYSTEM,                 PromptTemplates.DRAFT_IP_RIGHTS_USER,                 4));
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
        hydrateFromMatter(request);
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
        hydrateFromMatter(request);
        // 20 min — a full 9-clause SaaS/MSA at ~95 tok/s can reach ~9 min, plus QA retries push it further.
        // The prior 5 min ceiling was killing streams mid-generation and leaving the user with 2-3 clauses.
        SseEmitter emitter = new SseEmitter(1_200_000L);
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

    // ── Async draft generation ─────────────────────────────────────────────────

    /**
     * Kick off an async draft, persisting progress + partial HTML to the given
     * DocumentMetadata row. The caller creates the row first (status=PENDING)
     * and hands us the id; we flip it to PROCESSING, stream updates, and end
     * at INDEXED (success) or FAILED (exception).
     *
     * Blocking semaphore acquire — if the 2 concurrent slots are busy, we queue
     * rather than reject. Users expect "submit and come back later" to eventually
     * run, not to error out.
     */
    @Async
    public void generateDraftAsync(UUID docId, DraftRequest request, String username) {
        try {
            draftSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markDraftFailed(docId, "Interrupted waiting for draft capacity");
            return;
        }
        try {
            doAsyncDraft(docId, request, username);
        } catch (Exception e) {
            log.error("Async draft {} failed", docId, e);
            markDraftFailed(docId, truncate(e.getMessage() != null ? e.getMessage() : "Generation failed", 500));
        } finally {
            draftSemaphore.release();
        }
    }

    private void doAsyncDraft(UUID docId, DraftRequest request, String username) throws Exception {
        hydrateFromMatter(request);

        DocumentMetadata doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalStateException("Async draft row " + docId + " vanished"));

        // ── Phase 0: move PENDING → PROCESSING ──
        doc.setProcessingStatus(ProcessingStatus.PROCESSING);
        doc.setLastProgressAt(Instant.now());
        doc.setCurrentClauseLabel("Planning sections");
        doc = documentRepository.save(doc);

        // ── Phase 1: plan sections ──
        String[] templateParts = loadTemplateParts(request.getTemplateId(), buildPlaceholderMap(request));
        List<String> plannedSections = planSections(request);
        log.info("Async draft {} section planner chose {} sections: {}", docId, plannedSections.size(), plannedSections);

        Map<String, String> sectionValues = new LinkedHashMap<>();
        for (String key : plannedSections) {
            ClauseSpec spec = CLAUSE_SPECS.get(key);
            sectionValues.put(key, "<em style='color:#9CA3AF'>&#x23F3; Generating " + spec.title() + " clause…</em>");
        }
        // Persist initial skeleton + totals
        doc.setTotalClauses(plannedSections.size());
        doc.setCompletedClauses(0);
        doc.setLastProgressAt(Instant.now());
        storeHtml(doc, buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues));
        doc = documentRepository.save(doc);

        // ── Phase 2: generate each section ──
        TerminologyManifest manifest = buildInitialManifest(request, plannedSections);
        Map<String, List<String>> allQaWarnings = new LinkedHashMap<>();

        for (int i = 0; i < plannedSections.size(); i++) {
            String key = plannedSections.get(i);
            ClauseSpec spec = CLAUSE_SPECS.get(key);

            doc.setCurrentClauseLabel(spec.title());
            doc.setLastProgressAt(Instant.now());
            doc = documentRepository.save(doc);

            DraftContext ctx = draftContextRetriever.retrieveForClause(key, request);
            ClauseResult result = generateClauseWithQa(
                    request, ctx, key, spec.systemPrompt(), spec.userPromptTemplate(),
                    spec.expectedSubclauses(), null, manifest);
            sectionValues.put(key, result.html());
            if ("DEFINITIONS".equals(key)) manifest = manifest.withDefinedTerms(extractDefinedTerms(result.html()));
            manifest = manifest.withAppendedClause(
                    summarizeClauseOutline(i + 1, spec.title(), result.html()),
                    stripToPlainWithCap(result.html(), 1500));
            if (!result.qaWarnings().isEmpty()) allQaWarnings.put(key, result.qaWarnings());

            // Persist partial HTML + progress after each clause
            doc.setCompletedClauses(i + 1);
            doc.setLastProgressAt(Instant.now());
            storeHtml(doc, buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues));
            doc = documentRepository.save(doc);
        }

        // ── Phase 3: mark complete ──
        runCoherenceScan(plannedSections, sectionValues, manifest); // log-only; result ignored for async
        doc.setCurrentClauseLabel(null);
        doc.setProcessingStatus(ProcessingStatus.INDEXED);
        doc.setLastProgressAt(Instant.now());
        documentRepository.save(doc);
        log.info("Async draft {} completed ({} clauses, {} qa warnings)",
                 docId, plannedSections.size(), allQaWarnings.size());
    }

    private void storeHtml(DocumentMetadata doc, String html) {
        try {
            String path = fileStorageService.store(doc.getId(), doc.getFileName(), html.getBytes());
            doc.setStoredPath(path);
            doc.setFileSize((long) html.length());
        } catch (IOException e) {
            log.warn("Async draft {}: partial HTML store failed — {}", doc.getId(), e.getMessage());
        }
    }

    private void markDraftFailed(UUID docId, String reason) {
        try {
            documentRepository.findById(docId).ifPresent(doc -> {
                doc.setProcessingStatus(ProcessingStatus.FAILED);
                doc.setErrorMessage(reason);
                doc.setLastProgressAt(Instant.now());
                documentRepository.save(doc);
            });
        } catch (Exception e) {
            log.error("Couldn't mark draft {} failed: {}", docId, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ── Core generation ────────────────────────────────────────────────────────

    private DraftResponse doGenerateDraft(DraftRequest request) {
        String[] templateParts = loadTemplateParts(request.getTemplateId(), buildPlaceholderMap(request));

        List<String> plannedSections = planSections(request);
        log.info("Section planner (sync) chose {} sections: {}", plannedSections.size(), plannedSections);

        Map<String, String> sectionValues = new LinkedHashMap<>();
        List<ClauseSuggestion> suggestions = new ArrayList<>();
        Map<String, List<String>> allQaWarnings = new LinkedHashMap<>();

        TerminologyManifest manifest = buildInitialManifest(request, plannedSections);
        int articleIndex = 0;
        for (String key : plannedSections) {
            ClauseSpec spec = CLAUSE_SPECS.get(key);
            articleIndex++;
            DraftContext ctx = draftContextRetriever.retrieveForClause(key, request);
            ClauseResult result = generateClauseWithQa(request, ctx, key, spec.systemPrompt(), spec.userPromptTemplate(), spec.expectedSubclauses(), null, manifest);
            sectionValues.put(key, result.html());
            if ("DEFINITIONS".equals(key)) manifest = manifest.withDefinedTerms(extractDefinedTerms(result.html()));
            manifest = manifest.withAppendedClause(
                    summarizeClauseOutline(articleIndex, spec.title(), result.html()),
                    stripToPlainWithCap(result.html(), 1500));
            if (!result.qaWarnings().isEmpty()) allQaWarnings.put(key, result.qaWarnings());

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

        List<CoherenceIssue> coherenceIssues = runCoherenceScan(plannedSections, sectionValues, manifest);
        if (!coherenceIssues.isEmpty()) {
            log.warn("Coherence scan found {} issue(s) across {} clauses", coherenceIssues.size(), plannedSections.size());
        }

        List<String> coherenceSummary = coherenceIssues.stream()
                .map(ci -> "[" + ci.clause() + "] " + ci.type() + ": " + ci.detail())
                .collect(Collectors.toList());

        return DraftResponse.builder()
                .draftHtml(buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues))
                .suggestions(suggestions)
                .qaWarnings(allQaWarnings.isEmpty() ? null : allQaWarnings)
                .coherenceIssues(coherenceSummary.isEmpty() ? null : coherenceSummary)
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
        TerminologyManifest manifest = buildInitialManifest(request, plannedSections);
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
            ClauseResult result = generateClauseWithQa(request, ctx, key, spec.systemPrompt(), spec.userPromptTemplate(), spec.expectedSubclauses(), emitter, manifest);
            sectionValues.put(key, result.html());
            if ("DEFINITIONS".equals(key)) manifest = manifest.withDefinedTerms(extractDefinedTerms(result.html()));
            manifest = manifest.withAppendedClause(
                    summarizeClauseOutline(i + 1, spec.title(), result.html()),
                    stripToPlainWithCap(result.html(), 1500));
            List<String> qaWarnings = result.qaWarnings();
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

        List<CoherenceIssue> coherenceIssues = runCoherenceScan(plannedSections, sectionValues, manifest);
        if (!coherenceIssues.isEmpty()) {
            log.warn("Coherence scan found {} issue(s)", coherenceIssues.size());
        }

        List<String> coherenceSummary = coherenceIssues.stream()
                .map(ci -> "[" + ci.clause() + "] " + ci.type() + ": " + ci.detail())
                .collect(Collectors.toList());

        Map<String, Object> completePayload = new LinkedHashMap<>();
        completePayload.put("type", "complete");
        completePayload.put("draftHtml", buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues));
        completePayload.put("suggestions", suggestions);
        completePayload.put("qaWarnings", allQaWarnings);
        if (!coherenceSummary.isEmpty()) {
            completePayload.put("coherenceIssues", coherenceSummary);
        }
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
                resolveContractTypeName(request),
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
            List<String> defaults = defaultSections(request.getTemplateId());
            if (!known.isEmpty()) {
                // Guard: if the planner returns fewer sections than the template's default, the
                // defaults win. Prevents the planner from silently truncating a 9-clause SaaS
                // draft down to [DEFINITIONS, CONFIDENTIALITY] (observed behaviour).
                if (known.size() < defaults.size()) {
                    log.warn("Section planner returned {} sections {} — fewer than template defaults ({}); using defaults {}",
                             known.size(), known, defaults.size(), defaults);
                    return defaults;
                }
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
        if (templateId == null) return defaultMsaSections();
        return switch (templateId.toLowerCase()) {
            case "nda"              -> List.of("DEFINITIONS", "CONFIDENTIALITY", "LIABILITY",
                                               "TERMINATION", "GOVERNING_LAW", "GENERAL_PROVISIONS");
            case "software_license",
                 "ip_license"      -> List.of("DEFINITIONS", "IP_RIGHTS", "PAYMENT", "LIABILITY",
                                               "TERMINATION", "GOVERNING_LAW", "GENERAL_PROVISIONS");
            case "employment"      -> List.of("DEFINITIONS", "SERVICES", "PAYMENT", "CONFIDENTIALITY",
                                               "IP_RIGHTS", "TERMINATION", "GOVERNING_LAW", "GENERAL_PROVISIONS");
            case "supply"          -> List.of("DEFINITIONS", "SERVICES", "PAYMENT", "FORCE_MAJEURE",
                                               "LIABILITY", "TERMINATION", "GOVERNING_LAW", "GENERAL_PROVISIONS");
            case "saas",
                 "fintech_msa",
                 "clinical_services" -> List.of("DEFINITIONS", "SERVICES", "PAYMENT", "CONFIDENTIALITY",
                                               "DATA_PROTECTION", "LIABILITY", "TERMINATION",
                                               "GOVERNING_LAW", "GENERAL_PROVISIONS");
            default                -> defaultMsaSections(); // vendor, msa, custom, unknown
        };
    }

    private static List<String> defaultMsaSections() {
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

    private static final int QA_MAX_RETRIES = 2;

    /**
     * Generates a clause and auto-retries up to QA_MAX_RETRIES times if the QA pass
     * detects unfilled placeholders or incomplete sub-clauses.
     * Returns the best result (last attempt) along with any residual QA warnings.
     */
    record ClauseResult(String html, List<String> qaWarnings) {}

    /**
     * Running state propagated across clause generations:
     *   - Party names + defined terms: ensure consistent terminology.
     *   - Style fingerprint: one-line directive (register, numbering, sentence length) fixed once per draft.
     *   - Section outlines: numbered summary of every prior article, so subsequent clauses know the structure.
     *   - Last clause text: full (capped) text of the immediately preceding clause for style continuity.
     */
    private record TerminologyManifest(
            String partyAName,
            String partyBName,
            List<String> definedTerms,
            String styleFingerprint,
            List<String> fullArticlePlan,
            List<String> sectionOutlines,
            String lastClauseText) {

        TerminologyManifest withDefinedTerms(List<String> terms) {
            return new TerminologyManifest(partyAName, partyBName, terms, styleFingerprint, fullArticlePlan, sectionOutlines, lastClauseText);
        }

        TerminologyManifest withAppendedClause(String outline, String fullText) {
            List<String> next = new ArrayList<>(sectionOutlines);
            next.add(outline);
            return new TerminologyManifest(partyAName, partyBName, definedTerms, styleFingerprint, fullArticlePlan, next, fullText);
        }
    }

    private TerminologyManifest buildInitialManifest(DraftRequest request, List<String> plannedSectionKeys) {
        return new TerminologyManifest(
                nullToDefault(request.getPartyA(), "the Service Provider"),
                nullToDefault(request.getPartyB(), "the Client"),
                List.of(),
                buildStyleFingerprint(request),
                buildArticlePlan(plannedSectionKeys),
                new ArrayList<>(),
                "");
    }

    private List<String> buildArticlePlan(List<String> sectionKeys) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sectionKeys.size(); i++) {
            ClauseSpec spec = CLAUSE_SPECS.get(sectionKeys.get(i));
            if (spec != null) out.add("Article " + (i + 1) + " \u2014 " + spec.title());
        }
        return out;
    }

    private String buildStyleFingerprint(DraftRequest request) {
        String jurisdiction = nullToDefault(request.getJurisdiction(), "India").toLowerCase();
        String register = jurisdiction.contains("india")
                ? "formal Indian legal English (Indian Contract Act, 1872 conventions)"
                : "formal legal English appropriate to the governing law";
        return register
                + "; sentences \u2264 35 words; sub-clause numbering \"<article>.<sub>\" (e.g. 2.1, 2.2, 2.3); "
                + "use defined terms with exact capitalisation; cite any article by number and title (e.g., \"Clause 8 (Termination)\") \u2014 forward references permitted.";
    }

    private List<String> extractDefinedTerms(String definitionsHtml) {
        List<String> terms = new ArrayList<>();
        String plain = definitionsHtml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\"'\\u201C\\u2018]([A-Z][A-Za-z ]{2,40})[\"'\\u201D\\u2019]\\s+means")
                .matcher(plain);
        while (m.find() && terms.size() < 20) terms.add(m.group(1).trim());
        return terms;
    }

    private String summarizeClauseOutline(int articleIndex, String title, String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("class=\"clause-sub\"><strong>([^<]+)</strong>")
                .matcher(html);
        List<String> nums = new ArrayList<>();
        while (m.find()) nums.add(m.group(1).replace(".", "").trim());
        String base = "Article " + articleIndex + " \u2014 " + title;
        return nums.isEmpty() ? base : base + " (sub-clauses: " + String.join(", ", nums) + ")";
    }

    private String stripToPlainWithCap(String html, int maxChars) {
        String plain = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= maxChars ? plain : plain.substring(0, maxChars) + "\u2026";
    }

    private String buildManifestConstraint(TerminologyManifest manifest) {
        StringBuilder sb = new StringBuilder(
                "\n\nTERMINOLOGY MANDATE \u2014 non-negotiable:\n" +
                "- Refer to the service provider/vendor ONLY as \"" + manifest.partyAName() + "\" \u2014 never Vendor, Supplier, Company, or any other name.\n" +
                "- Refer to the client/customer ONLY as \"" + manifest.partyBName() + "\" \u2014 never Customer, Buyer, or any other name.\n" +
                "- Do NOT introduce any party name not listed above.\n");
        if (!manifest.definedTerms().isEmpty()) {
            sb.append("- Use these defined terms consistently (exact capitalisation): ")
              .append(String.join(", ", manifest.definedTerms())).append(".\n");
        }
        if (manifest.styleFingerprint() != null && !manifest.styleFingerprint().isBlank()) {
            sb.append("\nSTYLE MANDATE: ").append(manifest.styleFingerprint()).append("\n");
        }
        if (!manifest.fullArticlePlan().isEmpty()) {
            sb.append("\nFULL ARTICLE MAP for this contract (forward references are allowed \u2014 cite any article by its number and title, even if it is drafted later):\n");
            for (String a : manifest.fullArticlePlan()) sb.append("  - ").append(a).append("\n");
        }
        if (!manifest.sectionOutlines().isEmpty()) {
            sb.append("\nPRIOR SECTIONS ALREADY DRAFTED (keep the same structure, numbering, and register):\n");
            for (String o : manifest.sectionOutlines()) sb.append("  - ").append(o).append("\n");
        }
        if (manifest.lastClauseText() != null && !manifest.lastClauseText().isBlank()) {
            sb.append("\nIMMEDIATELY PRECEDING CLAUSE (for style continuity \u2014 do NOT repeat its content):\n")
              .append(manifest.lastClauseText()).append("\n");
        }
        return sb.toString();
    }

    private ClauseResult generateClauseWithQa(DraftRequest request, DraftContext ctx,
                                               String clauseKey, String systemPrompt,
                                               String userPromptTemplate, int expectedSubclauses,
                                               SseEmitter emitter, TerminologyManifest manifest) {
        String contractType = resolveContractTypeName(request);
        String jurisdiction = nullToDefault(request.getJurisdiction(), "India");
        String counterparty = nullToDefault(request.getCounterpartyType(), "general");
        String practiceArea = nullToDefault(request.getPracticeArea(), "general");
        String dealContext = buildDealContext(request);

        String initialPrompt = String.format(userPromptTemplate, contractType, jurisdiction, counterparty, practiceArea, dealContext, ctx.structuredContext());

        if (ctx.chunkCount() > 0) {
            log.info("Draft context: {} chunks from {} sources", ctx.chunkCount(), ctx.sourceDocuments().size());
        }

        String manifestConstraint = (manifest != null) ? buildManifestConstraint(manifest) : "";
        String ragGrounding = ctx.chunkCount() > 0
                ? "\n\nRAG PRECEDENT — reference only, NOT text to copy:\n" +
                  "The firm's precedent clauses in the user message are reference material for STYLE and STRUCTURE only.\n" +
                  "Write an ORIGINAL clause in the same register, tailored to THIS contract's parties and deal context.\n" +
                  "Never copy source tags, filenames, party names, or deal-specific details from the precedent.\n" +
                  "If the precedent contains text that looks like a different deal (different parties, different industry, different transaction type), ignore that text and draft fresh.\n"
                : "";
        String localizedSystemPrompt = legalSystemConfig.localizeForJurisdiction(systemPrompt, jurisdiction)
                + manifestConstraint + ragGrounding + PromptTemplates.DRAFT_CONTENT_GUARDRAILS;
        String fullSystemAndInitial = localizedSystemPrompt + "\n\n" + initialPrompt;

        // Generate initial attempt
        String generated = sanitizeClauseText(stripLlmArtifacts(
                chatModel.generate(UserMessage.from(fullSystemAndInitial)).content().text().trim()));
        List<String> qaWarnings = qaClause(clauseKey, generated, expectedSubclauses, contractType);

        // Best-of-N tracking — keep the highest-scoring attempt across retries
        String bestGenerated = generated;
        List<String> bestWarnings = qaWarnings;
        int bestScore = scoreAttempt(generated, qaWarnings);

        for (int attempt = 1; attempt <= QA_MAX_RETRIES && !qaWarnings.isEmpty(); attempt++) {
            log.warn("QA [{}] attempt {}/{}: {} issues — retrying (current best score: {})",
                    clauseKey, attempt, QA_MAX_RETRIES, qaWarnings.size(), bestScore);

            if (emitter != null) {
                try {
                    String summary = qaWarnings.stream().map(w -> "- " + w).collect(Collectors.joining("\n"));
                    emitter.send(SseEmitter.event().data(toJson(Map.of(
                            "type", "clause_retry",
                            "clauseType", clauseKey,
                            "attempt", attempt,
                            "fixing", summary
                    ))));
                } catch (IOException ignored) {}
            }

            // PHASE 3: Per-sub-clause regeneration when only sub-clauses are missing
            boolean onlyMissingSubclauses = qaWarnings.stream()
                    .allMatch(w -> w.startsWith("Incomplete:") || w.contains("Heading-only"));
            String retryGenerated;
            if (onlyMissingSubclauses && countSubClauses(generated) > 0) {
                retryGenerated = regenerateMissingSubclauses(
                        generated, expectedSubclauses, localizedSystemPrompt, initialPrompt);
            } else {
                // PHASE 2: Isolated retry — fresh prompt, NOT concatenated with original.
                String isolatedRetryPrompt = buildIsolatedRetryPrompt(
                        localizedSystemPrompt, initialPrompt, qaWarnings, expectedSubclauses, clauseKey);
                retryGenerated = sanitizeClauseText(stripLlmArtifacts(
                        chatModel.generate(UserMessage.from(isolatedRetryPrompt)).content().text().trim()));
            }

            List<String> retryWarnings = qaClause(clauseKey, retryGenerated, expectedSubclauses, contractType);
            int retryScore = scoreAttempt(retryGenerated, retryWarnings);

            // Best-of-N: keep whichever scored higher
            if (retryScore > bestScore) {
                log.info("QA [{}] attempt {}: improved (score {} -> {})", clauseKey, attempt, bestScore, retryScore);
                bestGenerated = retryGenerated;
                bestWarnings = retryWarnings;
                bestScore = retryScore;
            } else {
                log.info("QA [{}] attempt {}: no improvement (score {} <= {}), keeping previous", clauseKey, attempt, retryScore, bestScore);
            }

            generated = retryGenerated;
            qaWarnings = retryWarnings;
        }

        // Use the best attempt across all retries, not necessarily the latest
        generated = bestGenerated;
        qaWarnings = bestWarnings;

        if (!qaWarnings.isEmpty()) {
            log.warn("QA [{}]: {} residual warning(s) after {} retries — applying post-processor", clauseKey, qaWarnings.size(), QA_MAX_RETRIES);
        }
        // Always post-process to replace any remaining placeholders with sensible defaults
        generated = postProcessPlaceholders(generated, request);
        return new ClauseResult(generated, qaClause(clauseKey, generated, expectedSubclauses, contractType));
    }

    /**
     * Score a generation attempt: higher is better.
     * Penalises QA warnings, artifacts, and short content. Rewards length up to a sane cap.
     */
    private int scoreAttempt(String html, List<String> warnings) {
        int score = 1000;
        // Each warning = -100 points
        score -= warnings.size() * 100;
        // Hard penalty for serious artifacts
        for (String w : warnings) {
            if (w.contains("LLM artifact")) score -= 200;
        }
        // Penalty for very short output
        String plain = html.replaceAll("<[^>]+>", " ").trim();
        if (plain.length() < 200) score -= 300;
        else if (plain.length() < 400) score -= 100;
        // Reward longer, substantive output (capped at 2000 chars to avoid runaway)
        score += Math.min(plain.length(), 2000) / 10;
        return score;
    }

    /**
     * Build an isolated retry prompt — the directive becomes a fresh user message,
     * not concatenated to the failed output. Prevents the model from regurgitating
     * the previous broken output or echoing the directive itself.
     */
    private String buildIsolatedRetryPrompt(String systemPrompt, String originalUserPrompt,
                                             List<String> warnings, int expectedSubclauses, String clauseKey) {
        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt).append("\n\n");
        sb.append(originalUserPrompt).append("\n\n");
        sb.append("Your previous attempt had the following issues:\n");
        for (String w : warnings) {
            sb.append("  - ").append(w).append("\n");
        }
        sb.append("\nWrite a fresh, complete ").append(clauseKey).append(" clause with exactly ")
          .append(expectedSubclauses).append(" numbered sub-clauses. ")
          .append("Output ONLY the numbered legal text. No commentary, no JSON, no chat tokens.");
        return sb.toString();
    }

    /**
     * Count the number of sub-clauses in HTML output by counting "clause-sub" markers.
     */
    private int countSubClauses(String html) {
        int count = 0;
        int idx = 0;
        while ((idx = html.indexOf("clause-sub", idx)) >= 0) {
            count++;
            idx += "clause-sub".length();
        }
        return count;
    }

    /**
     * PHASE 3: Generate ONLY the missing sub-clauses and splice them into the existing HTML.
     * Preserves sub-clauses that already exist instead of regenerating from scratch.
     */
    private String regenerateMissingSubclauses(String existingHtml, int expectedSubclauses,
                                                 String systemPrompt, String originalUserPrompt) {
        int existing = countSubClauses(existingHtml);
        int missing = expectedSubclauses - existing;
        if (missing <= 0) return existingHtml;

        log.info("Regenerating {} missing sub-clauses (have {}, need {})", missing, existing, expectedSubclauses);

        // Build a focused prompt asking only for the missing sub-clauses
        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt).append("\n\n");
        sb.append(originalUserPrompt).append("\n\n");
        sb.append("You have already drafted ").append(existing).append(" sub-clauses for this clause. ");
        sb.append("Now write ONLY sub-clauses ").append(existing + 1).append(" through ").append(expectedSubclauses).append(". ");
        sb.append("Begin each with the appropriate number (e.g. \"").append(existing + 1).append(".\"). ");
        sb.append("Output ONLY the new numbered sub-clauses as plain legal prose. ");
        sb.append("Do not repeat the existing sub-clauses. Do not add commentary.");

        String additionalText = sanitizeClauseText(stripLlmArtifacts(
                chatModel.generate(UserMessage.from(sb.toString())).content().text().trim()));

        if (additionalText.isBlank()) {
            log.warn("Sub-clause regeneration returned empty result, keeping original");
            return existingHtml;
        }

        // Splice: append the new sub-clauses to the existing HTML
        return existingHtml + "\n" + additionalText;
    }

    /**
     * Post-generation QA pass. Detects unfilled placeholders and incomplete sub-clause counts.
     * Returns a list of human-readable warning strings (empty = clean).
     */
    private List<String> qaClause(String clauseKey, String htmlText, int expectedSubclauses) {
        return qaClause(clauseKey, htmlText, expectedSubclauses, null);
    }

    /** Phrases that clearly belong to a different clause type. Returns any that appeared. */
    private List<String> detectCrossArticleBleed(String clauseKey, String plain) {
        // Heading phrases that, if seen INSIDE this clause, mean the model pasted in
        // content from a different clause type. Keyed by "clause being drafted".
        Map<String, List<String>> forbidden = Map.of(
            "SERVICES", List.of("Termination for Convenience", "Termination for Cause",
                                 "Effect of Termination", "Force Majeure Event", "Limitation of Liability",
                                 "Indemnification Obligations"),
            "PAYMENT",  List.of("Termination for Convenience", "Termination for Cause",
                                 "Force Majeure Event", "Limitation of Liability",
                                 "Indemnification Obligations", "Confidential Information"),
            "CONFIDENTIALITY", List.of("Termination for Convenience", "Force Majeure Event",
                                 "Limitation of Liability", "Payment Schedule", "Scope of Services"),
            "IP_RIGHTS", List.of("Termination for Convenience", "Force Majeure Event",
                                 "Payment Schedule", "Scope of Services", "Limitation of Liability"),
            "LIABILITY", List.of("Scope of Services", "Payment Schedule", "Force Majeure Event",
                                 "Confidential Information obligations"),
            "TERMINATION", List.of("Scope of Services", "Payment Schedule", "Force Majeure Event",
                                 "Limitation of Liability"),
            "FORCE_MAJEURE", List.of("Scope of Services", "Payment Schedule", "Limitation of Liability",
                                 "Termination for Convenience"),
            "GOVERNING_LAW", List.of("Scope of Services", "Payment Schedule", "Force Majeure Event",
                                 "Limitation of Liability", "Termination for Convenience"),
            "DATA_PROTECTION", List.of("Scope of Services", "Payment Schedule", "Force Majeure Event",
                                 "Termination for Convenience", "Limitation of Liability")
        );
        List<String> blacklist = forbidden.get(clauseKey);
        if (blacklist == null) return List.of();
        List<String> hits = new ArrayList<>();
        for (String phrase : blacklist) {
            if (plain.contains(phrase)) hits.add(phrase);
        }
        return hits;
    }

    private List<String> qaClause(String clauseKey, String htmlText, int expectedSubclauses, String contractType) {
        List<String> warnings = new ArrayList<>();

        // Strip HTML tags for plain-text analysis
        String plain = htmlText.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();

        // 0. Sub-clause numbering consistency — all sub-clauses inside a single
        //    article must share the same leading number. Seen in the wild: a
        //    Services article with sub-clauses 4.1, 4.2, 4.3 (copied verbatim
        //    from a precedent where this was Article 4). Any mixed prefixes here
        //    means the model dumped a different article wholesale.
        java.util.regex.Matcher numMatcher = java.util.regex.Pattern
                .compile("class=\"clause-sub\"><strong>(\\d+)\\.(\\d+)\\.?</strong>")
                .matcher(htmlText);
        java.util.Set<String> articlePrefixes = new java.util.LinkedHashSet<>();
        while (numMatcher.find()) articlePrefixes.add(numMatcher.group(1));
        if (articlePrefixes.size() > 1) {
            warnings.add("Sub-clause numbering inconsistent — mixed article prefixes " + articlePrefixes
                    + ". All sub-clauses in a single clause must share the same leading number.");
            log.warn("QA [{}]: mixed sub-clause prefixes {}", clauseKey, articlePrefixes);
        }

        // 0b. Cross-article content bleed — a clause must not include headings
        //    that belong to a DIFFERENT clause type. Seen in the wild: Article 2
        //    (Services) containing "Termination for Convenience", "Termination
        //    for Cause", "Effect of Termination" sub-clauses.
        java.util.List<String> bleedHeadings = detectCrossArticleBleed(clauseKey, plain);
        if (!bleedHeadings.isEmpty()) {
            warnings.add("Cross-article content bleed — this " + clauseKey
                    + " clause contains headings from other articles: " + bleedHeadings
                    + ". Rewrite using only " + clauseKey + "-appropriate sub-clauses.");
            log.warn("QA [{}]: cross-article bleed {}", clauseKey, bleedHeadings);
        }

        // 1. Placeholder detection
        java.util.regex.Matcher m = QA_PLACEHOLDER_PATTERN.matcher(plain);
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        while (m.find()) found.add(m.group());
        for (String ph : found) {
            warnings.add("Unfilled placeholder: " + ph);
            log.warn("QA [{}]: unfilled placeholder detected — {}", clauseKey, ph);
        }

        // 2. Sub-clause count check
        if (expectedSubclauses > 0) {
            int actual = 0;
            int idx = 0;
            String marker = "clause-sub";
            while ((idx = htmlText.indexOf(marker, idx)) >= 0) { actual++; idx += marker.length(); }
            if (actual < expectedSubclauses) {
                String msg = "Incomplete: expected " + expectedSubclauses + " sub-clauses, found " + actual
                        + " — write ALL " + expectedSubclauses + " sub-clauses with full legal text";
                warnings.add(msg);
                log.warn("QA [{}]: {}", clauseKey, msg);
            }
        }

        // 3. Heading-only sub-clause detection — sub-clause body must have at least 40 chars after the number
        java.util.regex.Pattern subBody = java.util.regex.Pattern.compile(
                "class=\"clause-sub\"><strong>[^<]+</strong>\\s*(.*?)</p>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher sb2 = subBody.matcher(htmlText);
        int thinCount = 0;
        while (sb2.find()) {
            String body = sb2.group(1).replaceAll("<[^>]+>", "").trim();
            if (body.length() < 40) thinCount++;
        }
        if (thinCount > 0) {
            String msg = thinCount + " sub-clause(s) contain headings only with no substantive text — "
                    + "write complete legal sentences (minimum 40 characters) for each numbered sub-clause";
            warnings.add(msg);
            log.warn("QA [{}]: {}", clauseKey, msg);
        }

        // 4. Overall content length sanity check
        if (plain.length() < 200) {
            warnings.add("Clause body is too short (" + plain.length() + " chars) — expand with complete legal text");
            log.warn("QA [{}]: clause too short — {} chars", clauseKey, plain.length());
        }

        // 5. LLM artifact detection — JSON, LaTeX, code comments, instruction tokens
        if (java.util.regex.Pattern.compile("\"[a-z_]+\"\\s*:\\s*[\"\\[{]").matcher(plain).find()) {
            warnings.add("LLM artifact: raw JSON detected in output — output must be plain legal prose only");
            log.warn("QA [{}]: JSON artifacts in output", clauseKey);
        }
        if (plain.contains("\\text{") || plain.contains("\\textbf{") || plain.contains("$\\text")) {
            warnings.add("LLM artifact: LaTeX notation detected — output must be plain text, not LaTeX");
            log.warn("QA [{}]: LaTeX artifacts in output", clauseKey);
        }
        if (java.util.regex.Pattern.compile("(?m)//\\s*\\w").matcher(plain).find()
                || plain.contains("/*") || plain.contains("*/")) {
            warnings.add("LLM artifact: code comments detected — output must be plain legal prose");
            log.warn("QA [{}]: code comment artifacts in output", clauseKey);
        }
        if (plain.contains("[INST]") || plain.contains("[/INST]") || plain.contains("<<SYS>>")) {
            warnings.add("LLM artifact: instruction tokens detected — output must not contain model control tokens");
            log.warn("QA [{}]: instruction token artifacts in output", clauseKey);
        }
        // Training-format markers from v3 (leaked past stripLlmArtifacts via e.g. slightly
        // different punctuation). Force a retry rather than keep the corrupted clause.
        java.util.regex.Matcher trainingToken = java.util.regex.Pattern
                .compile("__[A-Z][A-Z0-9_]{2,}__")
                .matcher(plain);
        if (trainingToken.find()) {
            warnings.add("LLM artifact: training-format marker '" + trainingToken.group()
                    + "' leaked into output — rewrite the clause without any __TOKEN__ markers");
            log.warn("QA [{}]: training marker {} in output", clauseKey, trainingToken.group());
        }
        // Federal-contracting regulation dumps (CFR / FAR) — only surface if not the user's domain.
        // If the contract is for federal government work, these are legit; otherwise flag.
        boolean federalDomain = contractType != null
                && (contractType.toLowerCase().contains("federal") || contractType.toLowerCase().contains("government"));
        if (!federalDomain) {
            java.util.regex.Matcher fedBoiler = java.util.regex.Pattern
                    .compile("\\b(?:CFR|FAR)\\s*§\\s*\\d")
                    .matcher(plain);
            if (fedBoiler.find()) {
                warnings.add("LLM artifact: federal regulation citation (" + fedBoiler.group()
                        + ") leaked into a non-federal contract — rewrite without CFR/FAR boilerplate");
                log.warn("QA [{}]: federal citation {} leaked into '{}'", clauseKey, fedBoiler.group(), contractType);
            }
        }

        // 6. Contract-type contamination check
        if (contractType != null) {
            List<String> contaminants = detectContamination(plain, contractType);
            if (!contaminants.isEmpty()) {
                warnings.add("Template contamination detected — content from a different contract type: "
                        + String.join(", ", contaminants)
                        + ". Remove these and replace with content appropriate for a " + contractType + " agreement.");
                log.warn("QA [{}]: contamination detected for contract type '{}': {}", clauseKey, contractType, contaminants);
            }
        }

        // 7. Semantic requirement check — clause must address expected legal concepts
        List<String> semanticReqs = CLAUSE_SEMANTIC_REQUIREMENTS.get(clauseKey);
        if (semanticReqs != null) {
            String lowerPlain = plain.toLowerCase();
            List<String> missingConcepts = new ArrayList<>();
            for (String req : semanticReqs) {
                if (!lowerPlain.contains(req.toLowerCase())) {
                    missingConcepts.add(req);
                }
            }
            if (!missingConcepts.isEmpty()) {
                String msg = "Missing required legal concepts for " + clauseKey + ": "
                        + String.join(", ", missingConcepts)
                        + " — your clause MUST address each of these";
                warnings.add(msg);
                log.warn("QA [{}]: missing semantic requirements: {}", clauseKey, missingConcepts);
            }
        }

        return warnings;
    }

    private static final Map<String, List<String>> CONTAMINATION_SIGNALS = Map.of(
        "SaaS", List.of("real property", "lease agreement", "lessee", "lessor", "landlord", "tenant", "mortgage", "premises", "rental"),
        "Non-Disclosure Agreement", List.of("service level", "uptime", "subscription fee", "software license", "source code", "purchase order"),
        "Employment Agreement", List.of("saas platform", "uptime guarantee", "api access", "software subscription", "real property"),
        "Supply Agreement", List.of("software license", "saas", "uptime", "source code", "real property", "employment"),
        "Master Services Agreement", List.of("real property", "lease", "tenant", "mortgage", "employment contract")
    );

    /**
     * Semantic requirements per clause type: keywords that MUST appear in the generated text.
     * These represent the minimum legally meaningful content each clause type must address.
     * If absent, QA flags with a targeted warning so the retry knows exactly what to add.
     */
    private static final Map<String, List<String>> CLAUSE_SEMANTIC_REQUIREMENTS = Map.of(
        "PAYMENT",          List.of("invoice", "net ", "due date", "late payment"),
        "LIABILITY",        List.of("shall not exceed", "aggregate", "indemnif"),
        "TERMINATION",      List.of("written notice", "material breach", "effect"),
        "CONFIDENTIALITY",  List.of("confidential information", "shall not disclose", "exception"),
        "IP_RIGHTS",        List.of("ownership", "license", "intellectual property"),
        "FORCE_MAJEURE",    List.of("force majeure", "notification", "suspend"),
        "GOVERNING_LAW",    List.of("governed by", "jurisdiction", "dispute"),
        "DATA_PROTECTION",  List.of("personal data", "security", "breach"),
        "REPRESENTATIONS_WARRANTIES", List.of("authoris", "compliance", "conflict")
    );

    private List<String> detectContamination(String plain, String contractType) {
        String lowerPlain = plain.toLowerCase();
        String lowerType = contractType.toLowerCase();
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : CONTAMINATION_SIGNALS.entrySet()) {
            if (lowerType.contains(entry.getKey().toLowerCase())) continue; // skip own contract type
            for (String signal : entry.getValue()) {
                if (lowerPlain.contains(signal.toLowerCase()) && !found.contains(signal)) {
                    found.add(signal);
                }
            }
        }
        return found;
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

    // ── LLM artifact stripper — runs BEFORE clause sanitizer ───────────────────

    /**
     * Strips non-prose artifacts that fine-tuned models (especially saul-legal-v3)
     * inject into draft output: JSON wrappers, LaTeX commands, code comments,
     * instruction tokens, and broken unicode escapes.
     */
    private String stripLlmArtifacts(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String t = raw;

        // 0. Truncate at retry directive bleed-through — model echoed our retry prompt
        //    These markers indicate the model started regurgitating the system prompt.
        String[] bleedMarkers = {
            "REWRITE REQUIRED",
            "MISSING SUB-CLAUSES:",
            "RULES FOR THIS REWRITE:",
            "INSTRUCTIONS: Perform rewrite",
            "ANCHOR: Follow the firm precedent",
            "PRESERVE sub-clauses that do NOT have issues",
            // Training-format markers leaked from v3's instruction-tuning corpus
            "__PROCESSED_REQUEST__",
            "__INSTRUCTION__",
            "__RESPONSE__",
            "__FOLLOW_UP_QUESTIONS__",
            "__FORBIDDEN_ACTIONS",
            "__CONFIRMATION_OF_UNDERSTANDING__",
            "__BEGIN_PREMIUM_INSTRUCTIONS__",
            "INSTRUCTION: Write an additional clause",
            "CONFIRMATION_OF_UNDERSTANDING",
            "FORBIDDEN_ACTIONS_I_WILL_NOT_TAKE",
            // Federal-contracting boilerplate that tended to pour in after legal-prose saturation
            "\nCFR § ",
            "\nFAR § ",
            "\nFAR §",
            "\nCFR §",
            "UNDERSTANDINGS AND ACKNOWLEDGMENTS, IN FEDERAL CONTRACTING FORM",
            "The Government Contractor"
        };
        for (String marker : bleedMarkers) {
            int idx = t.indexOf(marker);
            if (idx > 0) {
                log.warn("Draft sanitizer: retry directive bleed detected at marker '{}', truncating", marker);
                t = t.substring(0, idx);
            }
        }
        // Generic catch-all for any __CAPS_WITH_UNDERSCORES__ pattern not listed above
        java.util.regex.Matcher genericArtifact = java.util.regex.Pattern
                .compile("__[A-Z][A-Z0-9_]{2,}__")
                .matcher(t);
        if (genericArtifact.find()) {
            int idx = genericArtifact.start();
            log.warn("Draft sanitizer: generic __TOKEN__ artifact at offset {} ('{}'), truncating",
                    idx, genericArtifact.group());
            t = t.substring(0, idx);
        }
        // RAG chunk headers that the model copied verbatim from the context block.
        // Two forms: "Source: some-file.pdf" and "[Source 10: filename | SECTION | ...".
        java.util.regex.Matcher ragHeader = java.util.regex.Pattern
                .compile("\\[Source\\s+\\d+:\\s*[^\\]]*(?:\\]|$)|Source:\\s*\\S+\\.(?:pdf|docx|htm[l]?|txt)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (ragHeader.find()) {
            int idx = ragHeader.start();
            log.warn("Draft sanitizer: RAG chunk header '{}' leaked at offset {}, truncating",
                    ragHeader.group(), idx);
            t = t.substring(0, idx);
        }
        // Pipe-delimited metadata the model copied from the RAG block
        // (e.g. "...Statement of Work | OTHER | ]..."). Rare in natural legal prose.
        java.util.regex.Matcher pipeMeta = java.util.regex.Pattern
                .compile("\\s\\|\\s+(?:OTHER|NDA|MSA|SAAS|EMPLOYMENT|VENDOR|USA|INDIA|UK)\\s+\\|")
                .matcher(t);
        if (pipeMeta.find()) {
            int idx = pipeMeta.start();
            log.warn("Draft sanitizer: pipe-delimited RAG metadata '{}' leaked, truncating",
                    pipeMeta.group().trim());
            t = t.substring(0, idx);
        }

        // 1. Strip instruction tokens
        // Mistral/Llama: [INST], [/INST], <<SYS>>, <s>, </s>
        // Gemma: <|end_of_turn|>, <|start_of_turn|>user, <|start_of_turn|>model, <|start_of_turn|>assistant
        // Qwen/ChatML: <|im_start|>, <|im_end|>
        t = t.replaceAll("\\[/?INST\\]", "")
             .replaceAll("<</?SYS>>", "")
             .replaceAll("</?s>", "")
             .replaceAll("<\\|end_of_turn\\|>", "")
             .replaceAll("<\\|start_of_turn\\|>(?:user|model|assistant|thought|system)?", "")
             .replaceAll("<\\|im_start\\|>(?:user|assistant|system)?", "")
             .replaceAll("<\\|im_end\\|>", "")
             // Also strip the bare role labels that appear after stripping <|start_of_turn|>
             .replaceAll("(?m)^\\s*(?:user|model|assistant|thought)\\s*$", "");

        // 2. Strip markdown code fences
        t = t.replaceAll("```(?:json|html|text)?\\s*", "")
             .replaceAll("```", "");

        // 3. Extract text from JSON wrappers — if the model outputs {"key": "actual text", ...}
        //    try to pull out the longest string value (the actual clause content)
        if (t.trim().startsWith("{") && t.trim().contains("\"")) {
            String extracted = extractProseFromJson(t);
            if (extracted != null && extracted.length() > 80) {
                t = extracted;
            }
        }

        // 4. Remove standalone JSON lines: lines that are just {"key": "value"} or JSON punctuation
        String[] lines = t.split("\\r?\\n");
        StringBuilder cleaned = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            // Skip lines that are pure JSON syntax
            if (trimmed.matches("^[{}\\[\\],]+$")) continue;
            if (trimmed.matches("^\"[a-z_]+\"\\s*:\\s*[\"\\[{].*")) continue;
            if (trimmed.matches("^\"[a-z_]+\"\\s*:\\s*\\d+.*")) continue;
            // Skip lines that are just JSON key with empty value
            if (trimmed.matches("^\"[a-z_]+\"\\s*:\\s*\"\"\\s*,?$")) continue;
            cleaned.append(line).append("\n");
        }
        t = cleaned.toString();

        // 5. Strip LaTeX notation: $\text{Name}$ → Name, \textbf{text} → text
        t = t.replaceAll("\\$\\\\text\\{([^}]*)\\}\\$", "$1")
             .replaceAll("\\\\text\\{([^}]*)\\}", "$1")
             .replaceAll("\\\\textbf\\{([^}]*)\\}", "$1")
             .replaceAll("\\\\textit\\{([^}]*)\\}", "$1")
             .replaceAll("\\\\section\\*?\\{([^}]*)\\}", "$1")
             .replaceAll("\\\\subsection\\*?\\{([^}]*)\\}", "$1")
             .replaceAll("\\\\emph\\{([^}]*)\\}", "$1");

        // 6. Strip code comments (// ... and /* ... */ and + // ...)
        t = t.replaceAll("\\+\\s*//[^\n]*", "")
             .replaceAll("(?m)^\\s*//[^\n]*$", "")
             .replaceAll("/\\*.*?\\*/", "");

        // 7. Fix broken unicode escapes rendered as literal text
        t = t.replace("\\u201c", "\u201c")
             .replace("\\u201d", "\u201d")
             .replace("\\u2019", "\u2019")
             .replace("\\u2014", "\u2014")
             .replace("\\u2013", "\u2013")
             .replace("\\xef\\x84\\xbc", ",");

        // 8. Strip ASCII table garbage (---|---|--- patterns)
        t = t.replaceAll("(?m)^[\\s|\\-]{10,}$", "");

        // 9. Remove residual JSON field names that leaked inline
        t = t.replaceAll("\"clause_name\"\\s*:\\s*\"[^\"]*\"\\s*,?", "")
             .replaceAll("\"clause_type\"\\s*:\\s*\"[^\"]*\"\\s*,?", "")
             .replaceAll("\"risk_level\"\\s*:\\s*\"[^\"]*\"\\s*,?", "")
             .replaceAll("\"rule_set\"\\s*:\\s*\"[^\"]*\"\\s*,?", "")
             .replaceAll("\"issue_id\"\\s*:\\s*\\d+\\s*,?", "")
             .replaceAll("\"issue_type\"\\s*:\\s*\"[^\"]*\"\\s*,?", "")
             .replaceAll("\"issue\"\\s*:\\s*\"[^\"]*\"\\s*,?", "")
             .replaceAll("\"rationale\"\\s*:\\s*\"[^\"]*\"\\s*,?", "");

        // 10. Extract content from "output", "suggested_language", or "fix_text" JSON fields
        t = t.replaceAll("\"(?:output|suggested_language|fix_text|fix)\"\\s*:\\s*\"", "")
             .replaceAll("\"\\s*,?\\s*$", "");

        // 11. Clean up multiple blank lines and trailing commas
        t = t.replaceAll("\\n{3,}", "\n\n")
             .replaceAll(",\\s*\\n\\s*\\}", "")
             .replaceAll("\\{\\s*\\}", "")
             .trim();

        // 12. Loop detection — truncate if same sentence appears 3+ times
        t = truncateOnRepetition(t);

        return t;
    }

    /**
     * Detects when the model gets stuck in a loop repeating the same sentence/phrase.
     * Truncates output at the second occurrence of any 40+ char fragment.
     */
    private String truncateOnRepetition(String text) {
        if (text == null || text.length() < 200) return text;
        // Split into sentences/segments
        String[] segments = text.split("(?<=[.!?\\n])");
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        StringBuilder kept = new StringBuilder();
        for (String seg : segments) {
            String norm = seg.trim().toLowerCase().replaceAll("\\s+", " ");
            if (norm.length() < 40) {
                kept.append(seg);
                continue;
            }
            // Use first 60 chars as fingerprint
            String fp = norm.substring(0, Math.min(60, norm.length()));
            int count = seen.getOrDefault(fp, 0) + 1;
            seen.put(fp, count);
            if (count >= 2) {
                log.warn("Draft sanitizer: repetition loop detected, truncating at: {}",
                        fp.substring(0, Math.min(50, fp.length())));
                break;
            }
            kept.append(seg);
        }
        return kept.toString();
    }

    /**
     * Attempts to extract the longest prose content from a JSON-wrapped LLM response.
     * Handles cases where the model wraps its output like: {"clause_name":"X","suggested_language":"actual text"}
     */
    private String extractProseFromJson(String jsonLike) {
        try {
            // Try to find all quoted string values and return the longest one
            java.util.regex.Pattern valuePattern = java.util.regex.Pattern.compile(
                    "\"(?:output|suggested_language|fix_text|fix|content|text|clause_text)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.regex.Matcher m = valuePattern.matcher(jsonLike);
            String longest = null;
            while (m.find()) {
                String val = m.group(1)
                        .replace("\\n", "\n")
                        .replace("\\t", " ")
                        .replace("\\\"", "\"");
                if (longest == null || val.length() > longest.length()) {
                    longest = val;
                }
            }
            return longest;
        } catch (Exception e) {
            log.debug("Failed to extract prose from JSON-like output: {}", e.getMessage());
            return null;
        }
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

    private String buildDirectiveRetry(List<String> warnings, String clauseKey, int expectedSubclauses) {
        StringBuilder sb = new StringBuilder("=== REWRITE REQUIRED — fix each issue below EXACTLY as instructed ===\n\n");
        for (String w : warnings) {
            if (w.startsWith("Unfilled placeholder:")) {
                String ph = w.replace("Unfilled placeholder:", "").trim();
                sb.append("PLACEHOLDER: ").append(ph).append(" must be replaced with a specific legal term. ")
                  .append("Do NOT use any square brackets. Use words like 'the Effective Date', ")
                  .append("'30 days', 'net 30 days', 'monthly in advance', 'as agreed in the Order Form'.\n");
            } else if (w.startsWith("Incomplete:")) {
                sb.append("MISSING SUB-CLAUSES: You must write exactly ").append(expectedSubclauses)
                  .append(" numbered sub-clauses. ")
                  .append("Keep any sub-clauses that are correct. Add the missing ones with full legal text. ")
                  .append("Each sub-clause needs 2+ complete sentences of substantive legal language.\n");
            } else if (w.contains("Heading-only") || w.contains("headings only")) {
                sb.append("EMPTY SUB-CLAUSES: Some numbered items have a title but no legal text. ")
                  .append("For EVERY numbered sub-clause, write 2-4 complete legal sentences after the heading. ")
                  .append("A heading alone (e.g. '4.1. Background IP') is NOT acceptable.\n");
            } else if (w.contains("too short")) {
                sb.append("TOO SHORT: Expand every sub-clause. Each must be a standalone enforceable provision ")
                  .append("of at least 2 sentences. Do not use bullet points or lists — write full prose.\n");
            } else if (w.contains("LLM artifact")) {
                sb.append("FORMAT ERROR: Your output contains non-prose artifacts (JSON, LaTeX, code comments, or instruction tokens). ")
                  .append("Output ONLY plain English legal text. No JSON objects, no \\text{}, no // comments, no [INST] tokens. ")
                  .append("Write numbered sub-clauses as plain prose sentences.\n");
            } else {
                sb.append("FIX: ").append(w).append("\n");
            }
        }
        sb.append("\nRULES FOR THIS REWRITE:\n")
          .append("- ANCHOR: Follow the firm precedent provided in the original request as your structural baseline.\n")
          .append("- Do NOT introduce new placeholders or brackets.\n")
          .append("- PRESERVE sub-clauses that do NOT have issues listed above — rewrite only the failing ones.\n")
          .append("- Output the COMPLETE rewritten clause (all sub-clauses, not just the fixed ones).\n")
          .append("- Use only the party names specified in the terminology mandate above.\n")
          .append("- Do NOT add new defined terms, new party roles, or new legal concepts not already in the original.\n");
        return sb.toString();
    }

    // ── Placeholder post-processor ────────────────────────────────────────────

    /**
     * Last-resort replacement of common unfilled placeholder patterns with
     * commercially reasonable defaults derived from the request context.
     * This runs after all QA retries so the output is never left with raw brackets.
     */
    private String postProcessPlaceholders(String html, DraftRequest request) {
        String partyA = nullToDefault(request.getPartyA(), "the Service Provider");
        String partyB = nullToDefault(request.getPartyB(), "the Client");
        String jurisdiction = nullToDefault(request.getJurisdiction(), "the governing jurisdiction");
        String noticeDays = nullToDefault(request.getNoticeDays(), "30");
        String termYears = nullToDefault(request.getTermYears(), "3");

        return html
            // Party name placeholders
            .replaceAll("(?i)\\[(?:Party A|Party 1|First Party|Service Provider|Company|Vendor)\\]", partyA)
            .replaceAll("(?i)\\[(?:Party B|Party 2|Second Party|Client|Customer|Buyer)\\]", partyB)
            .replaceAll("(?i)\\[(?:party name|name of party|insert party)\\]", partyA)
            // Date placeholders
            .replaceAll("(?i)\\[(?:effective date|commencement date|start date|date)\\]", "the Effective Date")
            .replaceAll("(?i)\\(insert(?:ion)? (?:effective )?date\\)", "the Effective Date")
            .replaceAll("(?i)\\[insert date\\]", "the Effective Date")
            // Duration placeholders
            .replaceAll("(?i)\\[(?:X+|N+|\\d*)\\s*(?:days?|months?|years?)\\]",
                    noticeDays + " (thirty) days")
            .replaceAll("(?i)\\(insert (?:notice )?period\\)", noticeDays + " days")
            .replaceAll("(?i)\\[insert duration\\]", termYears + " years")
            .replaceAll("(?i)\\[insert (?:initial )?term\\]", termYears + " years")
            // Financial placeholders
            .replaceAll("(?i)\\[insert (?:billing|payment) cycle\\]", "monthly in advance")
            .replaceAll("(?i)\\[insert (?:applicable )?interest rate\\]",
                    "2% per annum above the applicable base rate")
            .replaceAll("(?i)\\[(?:insert )?(?:amount|fee|price|rate|sum|\\$+|£+|₹+|\\*+)\\]",
                    "the amounts set forth in the applicable Statement of Work")
            // Jurisdiction placeholders
            .replaceAll("(?i)\\[(?:jurisdiction|governing law|applicable law|state|country)\\]", jurisdiction)
            // Generic catch-all: [***] and similar redaction markers
            .replaceAll("\\[\\*{2,}\\]", "as mutually agreed in writing by the Parties")
            .replaceAll("\\[_{2,}\\]", "as mutually agreed in writing by the Parties")
            // (insert ...) patterns not already caught
            .replaceAll("(?i)\\(insert[^)]{0,60}\\)", "as specified in the applicable Order Form")
            // TBD / TBC
            .replaceAll("(?i)\\bTBD\\b", "as mutually agreed by the Parties in writing")
            .replaceAll("(?i)\\bTBC\\b", "to be confirmed by written notice")
            // Universal catch-all: any remaining [INSERT ...] or [SPECIFY ...] bracket
            .replaceAll("(?i)\\[insert\\s+[^\\]]{1,80}\\]", "as specified in the applicable Order Form or Statement of Work")
            .replaceAll("(?i)\\[specify\\s+[^\\]]{1,80}\\]", "as agreed in writing by the Parties")
            // Any remaining ALL-CAPS bracket placeholder e.g. [PAYMENT PERIOD], [RATE OF INTEREST], [NUMBER]
            .replaceAll("\\[[A-Z][A-Z\\s]{1,60}\\]", "as mutually agreed by the Parties in writing")
            // Any remaining mixed-case bracket e.g. [insert number], [number of days]
            .replaceAll("\\[[a-zA-Z][a-zA-Z\\s]{1,60}\\]", "as mutually agreed by the Parties in writing");
    }

    // ── Post-generation coherence scan ────────────────────────────────────────

    private record CoherenceIssue(String clause, String type, String detail) {}

    /**
     * Scans all generated clauses for cross-clause consistency issues:
     * party name drift and defined term usage inconsistency.
     * Returns a list of issues found (empty = coherent).
     */
    private List<CoherenceIssue> runCoherenceScan(List<String> sections,
                                                   Map<String, String> sectionValues,
                                                   TerminologyManifest manifest) {
        List<CoherenceIssue> issues = new ArrayList<>();

        // Party name synonyms that indicate drift
        List<String> vendorSynonyms  = List.of("Vendor", "Supplier", "Company", "Contractor", "Provider");
        List<String> clientSynonyms  = List.of("Customer", "Buyer", "Purchaser", "Recipient");

        for (String key : sections) {
            String html = sectionValues.getOrDefault(key, "");
            if (html.isBlank()) continue;
            // strip HTML for plain text analysis
            String plain = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");

            // Check party name drift if manifest is available
            if (manifest != null) {
                for (String syn : vendorSynonyms) {
                    if (!manifest.partyAName().contains(syn)
                            && java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(syn) + "\\b")
                                   .matcher(plain).find()) {
                        issues.add(new CoherenceIssue(key, "PARTY_NAME_DRIFT",
                                "Party A referred to as '" + syn + "' but manifest says '" + manifest.partyAName() + "'"));
                    }
                }
                for (String syn : clientSynonyms) {
                    if (!manifest.partyBName().contains(syn)
                            && java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(syn) + "\\b")
                                   .matcher(plain).find()) {
                        issues.add(new CoherenceIssue(key, "PARTY_NAME_DRIFT",
                                "Party B referred to as '" + syn + "' but manifest says '" + manifest.partyBName() + "'"));
                    }
                }

                // Check defined term consistency — if DEFINITIONS defined a term, other clauses should use it
                for (String term : manifest.definedTerms()) {
                    if ("CONFIDENTIALITY".equals(key) && term.equalsIgnoreCase("Confidential Information")) {
                        if (!plain.contains("Confidential Information") && !plain.contains("confidential information")) {
                            issues.add(new CoherenceIssue(key, "DEFINED_TERM_MISSING",
                                    "CONFIDENTIALITY clause does not use defined term 'Confidential Information'"));
                        }
                    }
                }
            }
        }

        return issues;
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
        m.put("PARTY_A_ADDRESS", nullToDefault(r.getPartyAAddress(), "its registered office"));
        m.put("PARTY_B_ADDRESS", nullToDefault(r.getPartyBAddress(), "its registered office"));
        m.put("PARTY_A_REP", nullToDefault(r.getPartyARep(), "its authorised signatory"));
        m.put("PARTY_B_REP", nullToDefault(r.getPartyBRep(), "its authorised signatory"));
        m.put("EFFECTIVE_DATE", nullToDefault(r.getEffectiveDate(), java.time.LocalDate.now().toString()));
        m.put("JURISDICTION", nullToDefault(r.getJurisdiction(), "India"));
        m.put("AGREEMENT_REF", nullToDefault(r.getAgreementRef(), "REF-001"));
        m.put("TERM_YEARS", nullToDefault(r.getTermYears(), "3"));
        m.put("NOTICE_DAYS", nullToDefault(r.getNoticeDays(), "30"));
        m.put("SURVIVAL_YEARS", nullToDefault(r.getSurvivalYears(), "5"));
        m.put("CONTRACT_TYPE_TITLE", resolveContractTypeName(r).toUpperCase());
        return m;
    }

    /** Resolves the human-readable contract type name for use in the template title and AI prompts. */
    private String resolveContractTypeName(DraftRequest r) {
        if (r.getContractTypeName() != null && !r.getContractTypeName().isBlank()) {
            return r.getContractTypeName();
        }
        // Derive from templateId
        if (r.getTemplateId() == null) return "Contract";
        return switch (r.getTemplateId().toLowerCase()) {
            case "nda"              -> "Non-Disclosure Agreement";
            case "msa"              -> "Master Services Agreement";
            case "saas"             -> "SaaS Subscription Agreement";
            case "software_license" -> "Software License Agreement";
            case "vendor"           -> "Vendor Agreement";
            case "supply"           -> "Supply Agreement";
            case "employment"       -> "Employment Agreement";
            case "ip_license"       -> "IP License Agreement";
            case "clinical_services"-> "Clinical Services Agreement";
            case "fintech_msa"      -> "Fintech Master Services Agreement";
            default                 -> r.getTemplateId().replace("_", " ");
        };
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
