package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.ClauseTypeRegistry;
import com.legalpartner.config.ClauseTypeRegistry.ClauseTypeConfig;
import com.legalpartner.config.ContractTypeRegistry;
import com.legalpartner.config.DenylistRegistry;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DraftService {

    private final TemplateService templateService;
    private final DraftContextRetriever draftContextRetriever;
    private final ChatLanguageModel chatModel;
    /** JSON-mode model — used for the pre-draft scratchpad (guided JSON). */
    private final ChatLanguageModel jsonChatModel;
    private final Semaphore draftSemaphore;
    private final LegalSystemConfig legalSystemConfig;
    private final MatterRepository matterRepository;
    private final DocumentMetadataRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final AnonymizationService anonymizationService;
    private final ClauseTypeRegistry clauseRegistry;
    private final ContractTypeRegistry contractRegistry;
    private final DenylistRegistry denylistRegistry;
    private final DynamicEntityDenylistService dynamicDenylist;
    private final DealSpecExtractor dealSpecExtractor;
    private final ClauseRuleEngine clauseRuleEngine;
    private final FixEngine fixEngine;
    private final DealCoverageScore dealCoverageScore;
    private final HtmlToDocxConverter htmlToDocxConverter;
    private final GoldenClauseLibrary goldenClauseLibrary;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DraftService(TemplateService templateService,
                        DraftContextRetriever draftContextRetriever,
                        ChatLanguageModel chatModel,
                        @org.springframework.beans.factory.annotation.Qualifier("jsonChatModel")
                        ChatLanguageModel jsonChatModel,
                        LegalSystemConfig legalSystemConfig,
                        MatterRepository matterRepository,
                        DocumentMetadataRepository documentRepository,
                        FileStorageService fileStorageService,
                        AnonymizationService anonymizationService,
                        ClauseTypeRegistry clauseRegistry,
                        ContractTypeRegistry contractRegistry,
                        DenylistRegistry denylistRegistry,
                        DynamicEntityDenylistService dynamicDenylist,
                        DealSpecExtractor dealSpecExtractor,
                        ClauseRuleEngine clauseRuleEngine,
                        FixEngine fixEngine,
                        DealCoverageScore dealCoverageScore,
                        HtmlToDocxConverter htmlToDocxConverter,
                        GoldenClauseLibrary goldenClauseLibrary,
                        @Value("${legalpartner.draft.max-concurrent:2}") int maxConcurrent) {
        this.templateService = templateService;
        this.draftContextRetriever = draftContextRetriever;
        this.chatModel = chatModel;
        this.jsonChatModel = jsonChatModel;
        this.legalSystemConfig = legalSystemConfig;
        this.matterRepository = matterRepository;
        this.documentRepository = documentRepository;
        this.anonymizationService = anonymizationService;
        this.clauseRegistry = clauseRegistry;
        this.contractRegistry = contractRegistry;
        this.denylistRegistry = denylistRegistry;
        this.dynamicDenylist = dynamicDenylist;
        this.dealSpecExtractor = dealSpecExtractor;
        this.clauseRuleEngine = clauseRuleEngine;
        this.fixEngine = fixEngine;
        this.dealCoverageScore = dealCoverageScore;
        this.htmlToDocxConverter = htmlToDocxConverter;
        this.goldenClauseLibrary = goldenClauseLibrary;
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

    // Clause-type configuration lives in resources/config/clauses.yml (loaded
    // at startup by ClauseTypeRegistry). No more hardcoded CLAUSE_SPECS here —
    // use clauseRegistry.get(key) wherever you need title / prompts / expected
    // subclauses. Adding a clause type: one YAML block, no code changes.

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

        // ── Phase 0a: Extract structured deal spec (single source of truth) ──
        String brief = request.getDealBrief() != null ? request.getDealBrief() : request.getDealContext();
        com.legalpartner.model.dto.DealSpec dealSpec = null;
        if (brief != null && !brief.isBlank()) {
            dealSpec = dealSpecExtractor.extract(brief);
            // Hydrate request fields from DealSpec for template rendering
            if (dealSpec.getPartyA() != null) {
                if (request.getPartyA() == null || request.getPartyA().isBlank())
                    request.setPartyA(dealSpec.getPartyA().getName());
                if (request.getPartyAAddress() == null || request.getPartyAAddress().isBlank())
                    request.setPartyAAddress(dealSpec.getPartyA().getAddress());
            }
            if (dealSpec.getPartyB() != null) {
                if (request.getPartyB() == null || request.getPartyB().isBlank())
                    request.setPartyB(dealSpec.getPartyB().getName());
                if (request.getPartyBAddress() == null || request.getPartyBAddress().isBlank())
                    request.setPartyBAddress(dealSpec.getPartyB().getAddress());
            }
            log.info("DealSpec extracted: partyA={}, partyB={}, fees={}, license={}",
                    dealSpec.getPartyA() != null ? dealSpec.getPartyA().getName() : "?",
                    dealSpec.getPartyB() != null ? dealSpec.getPartyB().getName() : "?",
                    dealSpec.getFees() != null ? dealSpec.getFees().getLicenseFee() : "?",
                    dealSpec.getLicense() != null ? dealSpec.getLicense().getType() : "?");
        }
        // Keep old extraction as fallback
        hydratePartiesFromDealBrief(request);

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
            ClauseTypeConfig spec = clauseRegistry.get(key);
            sectionValues.put(key, "<em style='color:#9CA3AF'>&#x23F3; Generating " + spec.title() + " clause…</em>");
        }
        // Persist initial skeleton + totals
        doc.setTotalClauses(plannedSections.size());
        doc.setCompletedClauses(0);
        doc.setLastProgressAt(Instant.now());
        storeHtml(doc, buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues));
        doc = documentRepository.save(doc);

        // ── Phase 2: generate each section with rule enforcement ──
        TerminologyManifest manifest = buildInitialManifest(request, plannedSections);
        Map<String, List<String>> allQaWarnings = new LinkedHashMap<>();
        Map<String, List<ClauseRuleEngine.RuleResult>> allRuleResults = new LinkedHashMap<>();
        List<FixEngine.AuditEntry> allAuditEntries = new ArrayList<>();
        final com.legalpartner.model.dto.DealSpec finalDealSpec = dealSpec;

        // Clause types that should be rendered deterministically when golden clause exists.
        // These are high-risk clauses where LLM hallucination causes legal errors.
        Set<String> deterministicPreferred = Set.of(
                "IP_RIGHTS", "PAYMENT", "SERVICES", "TERMINATION", "GOVERNING_LAW");

        for (int i = 0; i < plannedSections.size(); i++) {
            String key = plannedSections.get(i);
            ClauseTypeConfig spec = clauseRegistry.get(key);

            doc.setCurrentClauseLabel(spec.title() + " (" + (i + 1) + "/" + plannedSections.size() + ")");
            doc.setLastProgressAt(Instant.now());
            doc = documentRepository.save(doc);

            // ── Deterministic-first: use golden clause if available for critical clauses ──
            ClauseResult result = null;
            if (deterministicPreferred.contains(key) && finalDealSpec != null) {
                var goldenClauses = goldenClauseLibrary.retrieveAll(
                        key, request.getTemplateId(),
                        finalDealSpec.getLegal() != null ? finalDealSpec.getLegal().getJurisdiction() : null,
                        request.getIndustry());
                if (!goldenClauses.isEmpty()) {
                    StringBuilder goldenHtml = new StringBuilder();
                    for (var gc : goldenClauses) {
                        String resolved = goldenClauseLibrary.resolve(gc, finalDealSpec, request.getIndustry());
                        if (!resolved.isBlank()) {
                            for (String line : resolved.split("\n")) {
                                if (line.isBlank()) continue;
                                goldenHtml.append("<p class=\"clause-sub\">").append(escapeHtmlText(line.trim())).append("</p>\n");
                            }
                        }
                    }
                    if (goldenHtml.length() > 100) {
                        result = new ClauseResult(goldenHtml.toString(), List.of());
                        log.info("Deterministic-first [{}]: rendered from {} golden clause(s), skipping LLM",
                                key, goldenClauses.size());
                    }
                }
            }

            // ── Fallback to LLM generation if no golden clause available ──
            if (result == null) {
                DraftContext ctx = draftContextRetriever.retrieveForClause(key, request);
                result = generateClauseWithQa(
                        request, ctx, key, spec.systemPrompt(), spec.userPromptTemplate(),
                        spec.expectedSubclauses(), null, manifest);
            }

            // ── Rule engine: validate + fix ──
            if (finalDealSpec != null) {
                List<ClauseRuleEngine.RuleResult> ruleResults = clauseRuleEngine.validate(
                        result.html(), key, finalDealSpec);
                List<ClauseRuleEngine.RuleResult> failures = ruleResults.stream()
                        .filter(r -> !r.passed()).toList();
                if (!failures.isEmpty()) {
                    log.info("Rule engine [{}]: {}/{} rules failed — running fix engine",
                            key, failures.size(), ruleResults.size());
                    FixEngine.FixResult fixResult = fixEngine.fix(
                            result.html(), failures, finalDealSpec,
                            spec.systemPrompt(), spec.userPromptTemplate());
                    if (fixResult.wasModified()) {
                        result = new ClauseResult(fixResult.fixedHtml(), result.qaWarnings());
                        log.info("Rule engine [{}]: fix applied ({} audit entries)",
                                key, fixResult.auditTrail().size());
                    }
                    allAuditEntries.addAll(fixResult.auditTrail());

                    // ── Post-fix BLOCK check: re-validate to catch unresolved BLOCK violations ──
                    List<ClauseRuleEngine.RuleResult> postFixResults = clauseRuleEngine.validate(
                            result.html(), key, finalDealSpec);
                    List<ClauseRuleEngine.RuleResult> residualBlocks = clauseRuleEngine.getBlockViolations(postFixResults);
                    if (!residualBlocks.isEmpty()) {
                        log.warn("Rule engine [{}]: {} BLOCK violation(s) still present after fix — applying deterministic templates",
                                key, residualBlocks.size());
                        String fixedHtml = applyDeterministicFallback(result.html(), key, residualBlocks, finalDealSpec);
                        if (!fixedHtml.equals(result.html())) {
                            result = new ClauseResult(fixedHtml, result.qaWarnings());
                            for (ClauseRuleEngine.RuleResult block : residualBlocks) {
                                allAuditEntries.add(new FixEngine.AuditEntry(
                                        block.rule().id(),
                                        block.message(),
                                        "Deterministic template fallback applied after BLOCK retries exhausted",
                                        "BLOCK",
                                        "CRITICAL"
                                ));
                            }
                        }
                    }
                    // Update rule results with post-fix validation
                    ruleResults = postFixResults;
                }

                // ── Deterministic template injection (for structured deal values) ──
                List<ClauseRuleEngine.DeterministicTemplate> templates =
                        clauseRuleEngine.getDeterministicTemplates(key, finalDealSpec);
                if (!templates.isEmpty()) {
                    String enhanced = applyDeterministicTemplates(result.html(), templates, finalDealSpec);
                    if (!enhanced.equals(result.html())) {
                        result = new ClauseResult(enhanced, result.qaWarnings());
                        log.info("Rule engine [{}]: {} deterministic template(s) applied",
                                key, templates.size());
                    }
                }

                allRuleResults.put(key, ruleResults);
            }
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

        // ── Phase 3: post-processing + mark complete ──
        runCoherenceScan(plannedSections, sectionValues, manifest); // log-only; result ignored for async

        // Build the full HTML, strip hallucinated amounts, then run missing terms detector
        String fullHtml = buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues);

        // Numeric consistency: strip any dollar amounts not in the DealSpec
        if (finalDealSpec != null) {
            fullHtml = stripHallucinatedAmounts(fullHtml, finalDealSpec);
        }

        List<String> missingTerms = detectMissingTerms(fullHtml, request);
        if (!missingTerms.isEmpty()) {
            log.warn("Draft {}: {} deal terms not found in output: {}", docId, missingTerms.size(), missingTerms);
        }

        // Compute deal coverage score
        if (finalDealSpec != null && !allRuleResults.isEmpty()) {
            DealCoverageScore.CoverageReport coverage = dealCoverageScore.compute(
                    allRuleResults, allAuditEntries, finalDealSpec);
            log.info("Draft {}: coverage={}, risk={}, blockers={}, fixes={}",
                    docId, String.format("%.0f%%", coverage.overallCoverage() * 100),
                    coverage.overallRisk(), coverage.blockers().size(), coverage.fixesApplied().size());
        }

        // Append input summary schedule to the draft
        String inputSummary = buildInputSummaryHtml(request);
        fullHtml = fullHtml.replace("</body>", inputSummary + "\n</body>");

        storeHtml(doc, fullHtml);

        // Also generate DOCX version for Word/OnlyOffice editing
        try {
            byte[] docxBytes = htmlToDocxConverter.convert(fullHtml);
            String docxPath = "/data/documents/" + docId + ".docx";
            java.nio.file.Files.write(java.nio.file.Path.of(docxPath), docxBytes);
            log.info("DOCX generated for draft {}: {} bytes", docId, docxBytes.length);
        } catch (Exception e) {
            log.warn("DOCX conversion failed for draft {} — HTML still available: {}", docId, e.getMessage());
        }

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
            ClauseTypeConfig spec = clauseRegistry.get(key);
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
            ClauseTypeConfig spec = clauseRegistry.get(key);
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
            ClauseTypeConfig spec = clauseRegistry.get(key);

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
                    .filter(clauseRegistry::contains)
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

    /**
     * Default clause sections for a given contract template. Now sourced from
     * resources/config/contract_types.yml — add a new template there, no Java
     * change needed here.
     */
    private List<String> defaultSections(String templateId) {
        return contractRegistry.defaultSections(templateId);
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
            ClauseTypeConfig spec = clauseRegistry.get(key);
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
            ClauseTypeConfig spec = clauseRegistry.get(sectionKeys.get(i));
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

    /**
     * Pre-draft mode scratchpad. Asks the jsonChatModel to self-declare the
     * contract mode + list 5-8 banned vocab terms + 5-8 required vocab terms
     * for the current (contract_type, clause_type) combination. The output is
     * injected verbatim into the main clause generation prompt so the model
     * commits to a mode before drafting.
     *
     * Cheap (small model, short output, ~500ms). Graceful-degrades to empty
     * string on any failure — drafting proceeds without the scratchpad rather
     * than erroring out.
     */
    private String buildScratchpadConstraint(String contractType, String clauseKey, int articleIndex) {
        try {
            String prompt = legalSystemConfig.localize(PromptTemplates.DRAFT_SCRATCHPAD_SYSTEM)
                    + "\n\n"
                    + String.format(PromptTemplates.DRAFT_SCRATCHPAD_USER, contractType, clauseKey, articleIndex);
            dev.langchain4j.data.message.AiMessage response = jsonChatModel.generate(
                    dev.langchain4j.data.message.UserMessage.from(prompt)
            ).content();
            String text = response.text().trim();
            // Extract JSON object from response (model may add surrounding text)
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.debug("Scratchpad [{}]: no JSON object in response, skipping", clauseKey);
                return "";
            }
            String json = text.substring(start, end + 1);
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            String mode = node.path("contract_mode").asText("");
            List<String> keyVocab = new ArrayList<>();
            node.path("key_vocabulary").forEach(v -> keyVocab.add(v.asText()));
            List<String> bannedVocab = new ArrayList<>();
            node.path("banned_vocabulary").forEach(v -> bannedVocab.add(v.asText()));
            if (mode.isBlank() && keyVocab.isEmpty() && bannedVocab.isEmpty()) return "";

            // Validate mode against contract_types.yml — override if model misclassified
            String expectedMode = contractRegistry.contractMode(
                    contractType.toLowerCase().replace(" ", "_").replace("-", "_"));
            if (expectedMode.isBlank()) expectedMode = resolveExpectedMode(contractType); // fallback
            if (!mode.isBlank() && !expectedMode.isBlank() && !mode.equalsIgnoreCase(expectedMode)) {
                log.warn("Scratchpad [{}]: model said mode={}, expected={} for '{}' — overriding",
                        clauseKey, mode, expectedMode, contractType);
                mode = expectedMode;
            }

            StringBuilder sb = new StringBuilder("\n\nMODE SCRATCHPAD (self-identified before drafting):\n");
            if (!mode.isBlank()) sb.append("- Contract mode: ").append(mode).append("\n");
            if (!keyVocab.isEmpty()) {
                sb.append("- Use these terms naturally in your clause: ")
                  .append(String.join(", ", keyVocab)).append("\n");
            }
            if (!bannedVocab.isEmpty()) {
                sb.append("- These terms belong to a DIFFERENT contract type and must not appear: ")
                  .append(String.join(", ", bannedVocab)).append("\n");
            }
            log.info("Scratchpad [{}/{}]: mode={}, {} key + {} banned terms",
                    clauseKey, articleIndex, mode, keyVocab.size(), bannedVocab.size());
            return sb.toString();
        } catch (Exception e) {
            log.debug("Scratchpad [{}]: failed ({}), proceeding without", clauseKey, e.getMessage());
            return "";
        }
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

        // Pre-draft mode scratchpad — model self-declares contract mode + banned/
        // required vocabulary BEFORE drafting. Cheapest fix for the SaaS→MSA mode
        // blur. Graceful-degrades to empty string on failure.
        int articleIndex = (manifest != null) ? manifest.sectionOutlines().size() + 1 : 1;
        String scratchpadConstraint = buildScratchpadConstraint(contractType, clauseKey, articleIndex);
        String manifestConstraint = (manifest != null) ? buildManifestConstraint(manifest) : "";
        String ragGrounding = ctx.chunkCount() > 0
                ? "\n\nRAG PRECEDENT — reference only, NOT text to copy:\n" +
                  "The firm's precedent clauses in the user message are reference material for STYLE and STRUCTURE only.\n" +
                  "Write an ORIGINAL clause in the same register, tailored to THIS contract's parties and deal context.\n" +
                  "Never copy source tags, filenames, party names, or deal-specific details from the precedent.\n" +
                  "If the precedent contains text that looks like a different deal (different parties, different industry, different transaction type), ignore that text and draft fresh.\n"
                : "";
        // Prompt assembly is ordered for vLLM prefix-cache reuse. Structure:
        //   [invariant across all requests]      ← GUARDRAILS (universal cache hit)
        //   [invariant across clauses in 1 draft] ← manifest + ragGrounding (within-draft hit)
        //   [clause-specific]                    ← localized system prompt + user prompt (miss)
        // Moving the invariants to the front means the first ~N tokens are identical
        // across every clause of a draft and every draft ever — vLLM's APC reuses the
        // KV cache for those tokens instead of re-computing prefill.
        // Pre-generation: inject deal-specific requirements from rule engine
        String dealRequirements = "";
        if (request.getExtractedDealTerms() != null) {
            // Use the new rule engine if DealSpec is available on the request
            dealRequirements = clauseRuleEngine.buildRequirementsPrompt(clauseKey, null);
        }

        String localizedSystemPrompt = legalSystemConfig.localizeForJurisdiction(systemPrompt, jurisdiction);
        String fullSystemAndInitial = PromptTemplates.DRAFT_CONTENT_GUARDRAILS
                + manifestConstraint
                + ragGrounding
                + scratchpadConstraint
                + dealRequirements
                + "\n\n" + localizedSystemPrompt
                + "\n\n" + initialPrompt;

        // Generate initial attempt
        String generated = sanitizeClauseText(stripLlmArtifacts(
                chatModel.generate(UserMessage.from(fullSystemAndInitial)).content().text().trim()));
        // Truncate excess sub-clauses — model sometimes generates 10+ when spec says 3
        if (expectedSubclauses > 0 && countSubClauses(generated) > expectedSubclauses + 2) {
            log.warn("Clause [{}]: {} sub-clauses generated, truncating to {}", clauseKey,
                    countSubClauses(generated), expectedSubclauses + 1);
            generated = truncateToNSubClauses(generated, expectedSubclauses + 1);
        }
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
        // Enforce consistent party naming from contract_types.yml
        generated = enforcePartyRoles(generated, request);

        // Output-side confidentiality check — detect concrete entities in the
        // draft (dollar figures with real amounts, specific dates with year,
        // emails, phones) and cross-check against what the user actually
        // supplied. Anything unaccounted for is a likely cross-client leak
        // from a precedent — flag it so the user sees the warning and the
        // coherence scan surfaces it in the final QA report.
        List<String> finalQa = qaClause(clauseKey, generated, expectedSubclauses, contractType);
        Set<String> detected = anonymizationService.detectConcreteEntities(generated);
        if (!detected.isEmpty()) {
            List<String> userSupplied = List.of(
                    nullSafe(request.getPartyA()),
                    nullSafe(request.getPartyB()),
                    nullSafe(request.getPartyAAddress()),
                    nullSafe(request.getPartyBAddress()),
                    nullSafe(request.getPartyARep()),
                    nullSafe(request.getPartyBRep()),
                    nullSafe(request.getEffectiveDate()),
                    nullSafe(request.getJurisdiction()),
                    nullSafe(request.getAgreementRef()),
                    nullSafe(request.getDealBrief()),
                    nullSafe(request.getContractTypeName()),
                    nullSafe(request.getTermYears()),
                    nullSafe(request.getNoticeDays()),
                    nullSafe(request.getSurvivalYears())
            );
            Set<String> leaks = anonymizationService.findUnjustified(detected, userSupplied);
            if (!leaks.isEmpty()) {
                finalQa = new ArrayList<>(finalQa);
                finalQa.add("Possible cross-client leak — concrete entities in the draft that were NOT in the user's brief/form: "
                        + leaks + ". These likely came from a precedent. Verify before sending.");
                log.warn("QA [{}]: possible cross-client leak entities {}", clauseKey, leaks);
            }
        }
        return new ClauseResult(generated, finalQa);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

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

    /** Truncate HTML to keep only the first N sub-clauses + any non-sub-clause preamble. */
    private String truncateToNSubClauses(String html, int maxClauses) {
        String[] parts = html.split("(?=<p class=\"clause-sub\">)");
        StringBuilder result = new StringBuilder();
        int clausesSeen = 0;
        for (String part : parts) {
            if (part.contains("clause-sub")) {
                clausesSeen++;
                if (clausesSeen > maxClauses) break;
            }
            result.append(part);
        }
        return result.toString().stripTrailing();
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
        // Combined forbidden-headings list:
        //   - YAML-configured (specific sub-clause labels like "Termination for Convenience")
        //   - Auto-derived (titles of every OTHER clause in the registry)
        // Adding a new clause type automatically gets its title added to every
        // OTHER clause's forbidden list — zero maintenance.
        if (!clauseRegistry.contains(clauseKey)) return List.of();
        List<String> blacklist = clauseRegistry.combinedForbiddenHeadings(clauseKey);
        if (blacklist == null || blacklist.isEmpty()) return List.of();
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

        // 7. Semantic requirement check — clause must address expected legal concepts.
        // Configured per clause type in clauses.yml (semantic_requirements).
        if (clauseRegistry.contains(clauseKey)) {
            List<String> semanticReqs = clauseRegistry.get(clauseKey).semanticRequirements();
            if (!semanticReqs.isEmpty()) {
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
        }

        // 8. Memorized-entity denylist — catches known training-data leaks
        //    (Ontario copyright law in non-Canadian contracts, Acme / Mahindra /
        //    NeuroPace party names from EDGAR precedents, specific $ figures).
        //    These tokens should only appear in output if the user specifically
        //    requested them — which the QA layer can't verify, so we flag all
        //    occurrences and let the retry clean them up.
        // Combined denylist = static seed (training-known leaks from YAML) +
        // dynamic firm-wide (entities auto-extracted from anonymization maps
        // of every uploaded precedent). The dynamic layer grows as the firm
        // uploads more docs; if their Client A's name was ever in a precedent,
        // it's on the list and won't be allowed to appear in Client B's draft.
        List<String> memorizedHits = new ArrayList<>();
        Set<String> combined = new java.util.LinkedHashSet<>(denylistRegistry.all());
        combined.addAll(dynamicDenylist.all());
        for (String entity : combined) {
            java.util.regex.Matcher em = java.util.regex.Pattern
                    .compile("\\b" + java.util.regex.Pattern.quote(entity) + "\\b",
                             java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(plain);
            if (em.find()) memorizedHits.add(entity);
        }
        if (!memorizedHits.isEmpty()) {
            warnings.add("Training-corpus entity leaked into output: " + memorizedHits
                    + " — rewrite without these names. Use only parties / jurisdictions / "
                    + "figures from the user's deal brief.");
            log.warn("QA [{}]: memorized entities leaked: {}", clauseKey, memorizedHits);
        }

        return warnings;
    }

    // Entity denylist now lives in resources/config/denylists.yml (loaded by
    // DenylistRegistry). Access via denylistRegistry.all() / .byCategory().

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
    // CLAUSE_SEMANTIC_REQUIREMENTS now lives in clauses.yml (semantic_requirements
    // per clause). Access via clauseRegistry.get(key).semanticRequirements().

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

    /**
     * If partyA/partyB are blank but dealBrief has party names, extract and set them
     * so the HTML template preamble shows real names instead of "Party A / Party B".
     */
    private void hydratePartiesFromDealBrief(DraftRequest request) {
        String brief = request.getDealBrief();
        if (brief == null || brief.isBlank()) {
            brief = request.getDealContext();
        }
        if (brief == null || brief.isBlank()) return;

        boolean needPartyA = request.getPartyA() == null || request.getPartyA().isBlank();
        boolean needPartyB = request.getPartyB() == null || request.getPartyB().isBlank();
        if (!needPartyA && !needPartyB) return;

        try {
            String extractPrompt = """
                    Extract ALL structured data from this deal brief. Output ONLY valid JSON:
                    {"partyA": "full legal name or null",
                     "partyB": "full legal name or null",
                     "partyAAddress": "full address or null",
                     "partyBAddress": "full address or null",
                     "partyARole": "Licensor/Provider/Seller/Landlord/Employer or null",
                     "partyBRole": "Licensee/Customer/Buyer/Tenant/Employee or null",
                     "keyTerms": ["term1: value1", "term2: value2", ...]}

                    Extract every concrete value: party names, addresses, monetary amounts,
                    user counts, SLA targets, license types, term durations, support levels,
                    special requirements (escrow, derivative works, patches, etc).

                    Deal brief:
                    """ + brief;
            String resp = jsonChatModel.generate(UserMessage.from(extractPrompt)).content().text().trim();
            int s = resp.indexOf('{'), e = resp.lastIndexOf('}');
            if (s < 0 || e <= s) return;
            var node = objectMapper.readTree(resp.substring(s, e + 1));

            if (needPartyA && node.has("partyA") && !node.get("partyA").isNull()) {
                request.setPartyA(node.get("partyA").asText());
                log.info("Hydrated partyA from deal brief: {}", request.getPartyA());
            }
            if (needPartyB && node.has("partyB") && !node.get("partyB").isNull()) {
                request.setPartyB(node.get("partyB").asText());
                log.info("Hydrated partyB from deal brief: {}", request.getPartyB());
            }
            // Hydrate addresses
            if ((request.getPartyAAddress() == null || request.getPartyAAddress().isBlank())
                    && node.has("partyAAddress") && !node.get("partyAAddress").isNull()) {
                request.setPartyAAddress(node.get("partyAAddress").asText());
            }
            if ((request.getPartyBAddress() == null || request.getPartyBAddress().isBlank())
                    && node.has("partyBAddress") && !node.get("partyBAddress").isNull()) {
                request.setPartyBAddress(node.get("partyBAddress").asText());
            }
            // Store extracted key terms for injection into every clause prompt
            if (node.has("keyTerms") && node.get("keyTerms").isArray()) {
                StringBuilder terms = new StringBuilder();
                node.get("keyTerms").forEach(t -> terms.append("- ").append(t.asText()).append("\n"));
                request.setExtractedDealTerms(terms.toString());
                log.info("Extracted {} deal terms from brief", node.get("keyTerms").size());
            }
        } catch (Exception ex) {
            log.debug("Failed to extract parties from deal brief: {}", ex.getMessage());
        }
    }

    /** Map contract type name to expected scratchpad mode for validation. */
    private String resolveExpectedMode(String contractType) {
        if (contractType == null) return "";
        String lower = contractType.toLowerCase();
        if (lower.contains("saas") || lower.contains("subscription")) return "SAAS";
        if (lower.contains("software") && lower.contains("license")) return "SOFTWARE_LICENSE";
        if (lower.contains("master service") || lower.contains("msa")) return "MSA";
        if (lower.contains("nda") || lower.contains("non-disclosure")) return "NDA";
        if (lower.contains("employment")) return "EMPLOYMENT";
        if (lower.contains("supply")) return "SUPPLY";
        if (lower.contains("ip") && lower.contains("license")) return "IP_LICENSE";
        return "";
    }

    private String buildDealContext(DraftRequest request) {
        StringBuilder sb = new StringBuilder();

        String brief = request.getDealBrief() != null ? request.getDealBrief() : request.getDealContext();
        if (brief != null && !brief.isBlank()) {
            sb.append("\nDeal brief: ").append(brief.strip()).append("\n");
        }

        // Inject extracted deal terms into every clause prompt so the model
        // uses actual values ($750K, 500 users) instead of generic placeholders
        String extractedTerms = request.getExtractedDealTerms();
        if (extractedTerms != null && !extractedTerms.isBlank()) {
            sb.append("\nDEAL TERMS — weave these values naturally into your legal prose:\n")
              .append(extractedTerms)
              .append("\nIMPORTANT: Incorporate these values INTO your clause text. Do NOT copy this list, ")
              .append("the deal brief, or any input parameters as a schedule, appendix, or separate section. ")
              .append("Output ONLY the clause body.\n");
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
            // Jurisdiction-aware regulatory references — don't inject Indian laws into US contracts
            String jur = request.getJurisdiction() != null ? request.getJurisdiction().toLowerCase() : "";
            boolean isUS = jur.contains("united states") || jur.contains("delaware") || jur.contains("california")
                    || jur.contains("new york") || jur.contains("texas") || jur.contains("usa");
            boolean isIndia = jur.contains("india") || jur.contains("mumbai") || jur.contains("delhi");

            String regRef;
            if (isUS) {
                regRef = switch (industry.toUpperCase()) {
                    case "FINTECH"        -> "Reference applicable US federal and state financial regulations where relevant.";
                    case "PHARMA"        -> "Reference FDA regulations, HIPAA, and applicable state healthcare laws where relevant.";
                    case "IT_SERVICES"   -> "Reference CCPA/CPRA, applicable state privacy laws, and SOC 2 compliance where relevant.";
                    case "MANUFACTURING" -> "Reference OSHA, EPA regulations, and applicable state environmental laws where relevant.";
                    default -> "";
                };
            } else if (isIndia) {
                regRef = switch (industry.toUpperCase()) {
                    case "FINTECH"        -> "Reference RBI guidelines, FEMA 1999, and Payment & Settlement Systems Act 2007 where relevant.";
                    case "PHARMA"        -> "Reference Drugs and Cosmetics Act 1940, Clinical Establishment Act 2010, and DPDPA 2023 where relevant.";
                    case "IT_SERVICES"   -> "Reference IT Act 2000, DPDPA 2023, and SEBI (if listed entity) where relevant.";
                    case "MANUFACTURING" -> "Reference Factories Act 1948, Environment Protection Act 1986, and GST Act 2017 where relevant.";
                    default -> "";
                };
            } else {
                regRef = "";
            }
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
            "The Government Contractor",
            // Mistral instruction tokens that leaked through vLLM
            "[/INST]",
            "[INST]",
            // Model copying input context verbatim as a "schedule"
            "SCHEDULE — DRAFT PARAMETERS",
            "SCHEDULE - DRAFT PARAMETERS",
            "Draft Parameters",
            // SaulLM-54B artifacts — model echoes system prompt structure markers
            "END OF ARTICLE",
            "END OF DOCUMENT",
            "END OF CLARIFICATIONS",
            "END OF CLAUSE",
            "The following sub-clauses were provided earlier",
            // Model self-commentary / meta-text that breaks document trust
            "Note: This clause is drafted",
            "Note: This payment clause",
            "Note: This clause complies",
            "Note: Keep the same structure",
            "Note: CCPA -",
            "[Note: the above sub-clauses",
            "[Note: this clause",
            "This clause is tailored",
            "Reference UCC Article",
            "Sub-clause 8:",
            "Sub-clause 9:",
            "Sub-clause 10:"
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

        // 13. NUCLEAR LINE-LEVEL SANITIZER — strip any line that is meta-commentary,
        //     not legal prose. Catches patterns the truncation markers miss.
        String[] sanitizeLines = t.split("\\n");
        StringBuilder sanitized = new StringBuilder();
        for (String sLine : sanitizeLines) {
            String trimmed = sLine.trim();
            if (trimmed.isEmpty()) { sanitized.append("\n"); continue; }
            // Kill meta-commentary lines
            if (isMetaCommentary(trimmed)) {
                log.debug("Draft sanitizer: stripped meta-commentary line: {}", trimmed.substring(0, Math.min(80, trimmed.length())));
                continue;
            }
            sanitized.append(sLine).append("\n");
        }
        t = sanitized.toString().trim();

        // 14. Strip bold markdown artifacts (**text**) — convert to plain text
        t = t.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        t = t.replaceAll("\\*\\*", "");

        return t;
    }

    /**
     * Numeric consistency pass: find all dollar amounts in the HTML and replace
     * any that don't match DealSpec values with the closest valid amount.
     * Prevents hallucinated figures ($600K, $885K) from appearing in output.
     */
    private String stripHallucinatedAmounts(String html, com.legalpartner.model.dto.DealSpec dealSpec) {
        // Collect all valid amounts from DealSpec
        Set<String> validAmounts = new java.util.HashSet<>();
        if (dealSpec.getFees() != null) {
            if (dealSpec.getFees().getLicenseFee() != null)
                validAmounts.add(String.format("%,d", dealSpec.getFees().getLicenseFee()));
            if (dealSpec.getFees().getMaintenanceFee() != null)
                validAmounts.add(String.format("%,d", dealSpec.getFees().getMaintenanceFee()));
        }
        if (validAmounts.isEmpty()) return html; // nothing to validate against

        // Find all $X,XXX patterns in the HTML
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$([\\d,]+)").matcher(html);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        int replacements = 0;

        while (m.find()) {
            String amount = m.group(1); // e.g., "600,000"
            if (!validAmounts.contains(amount)) {
                // This amount is not in the deal — replace with the closest valid amount
                // or remove the entire containing sentence if it's clearly wrong
                log.warn("Numeric consistency: hallucinated amount ${} found, removing", amount);
                result.append(html, lastEnd, m.start());
                result.append("[amount per applicable Schedule]");
                lastEnd = m.end();
                replacements++;
            }
        }

        if (replacements > 0) {
            result.append(html, lastEnd, html.length());
            log.info("Numeric consistency: replaced {} hallucinated amount(s)", replacements);
            return result.toString();
        }
        return html;
    }

    /** Returns true if the line is model meta-commentary, not legal prose. */
    private boolean isMetaCommentary(String line) {
        String lower = line.toLowerCase();
        // Exact prefix patterns — high confidence
        if (lower.startsWith("note:")) return true;
        if (lower.startsWith("[note:")) return true;
        if (lower.startsWith("reference:")) return true;
        if (lower.startsWith("source:")) return true;
        if (lower.startsWith("instruction:")) return true;
        if (lower.startsWith("end of ")) return true;
        if (lower.startsWith("do not ")) return true;
        if (lower.startsWith("sub-clause ") && lower.contains(":")) {
            // "Sub-clause 8: ..." overflow past expected count
            try {
                int num = Integer.parseInt(lower.substring(11, lower.indexOf(':')).trim());
                return num > 7; // strip overflow sub-clauses numbered 8+
            } catch (NumberFormatException ignored) {}
        }
        // Containment patterns — model explaining itself
        if (lower.contains("this clause is drafted")) return true;
        if (lower.contains("this clause is tailored")) return true;
        if (lower.contains("this clause complies")) return true;
        if (lower.contains("this payment clause")) return true;
        if (lower.contains("the following sub-clauses were provided")) return true;
        if (lower.contains("do not include sub-clause headings")) return true;
        if (lower.contains("keep the same structure")) return true;
        if (lower.contains("as per the given prompt")) return true;
        if (lower.contains("the above sub-clauses")) return true;
        if (lower.contains("are not required to be included")) return true;
        if (lower.contains("the data protection and privacy focus")) return true;
        if (lower.contains("this is a first draft")) return true;
        // Fix engine / retry prompt leaks
        if (lower.startsWith("fixed clause")) return true;
        if (lower.startsWith("fixing")) return true;
        if (lower.contains("fixing") && lower.contains("violation")) return true;
        if (lower.contains("corrected clause")) return true;
        if (lower.contains("deal values embedded")) return true;
        if (lower.startsWith("here is the")) return true;
        if (lower.startsWith("this version satisfies")) return true;
        if (lower.startsWith("this satisfies")) return true;
        if (lower.startsWith("the corrected version")) return true;
        if (lower.startsWith("article ") && lower.contains(". ") && lower.length() < 30) return true; // "Article 2. Services" heading echo
        if (lower.contains("satisfies all requirements")) return true;
        if (lower.contains("stated above")) return true;
        if (lower.contains("```")) return true;
        if (lower.startsWith("to summarize")) return true;
        if (lower.startsWith("drafting complete")) return true;
        if (lower.startsWith("to conclude")) return true;
        if (lower.startsWith("in summary")) return true;
        if (lower.startsWith("the above clauses")) return true;
        return false;
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

    // ── Party role enforcement ─────────────────────────────────────────────────

    /**
     * Replaces inconsistent party naming (Enterprise, Client, Vendor, Company, etc.)
     * with the correct roles from contract_types.yml (e.g. Licensor/Licensee).
     * Runs after all generation — deterministic string replacement, no LLM needed.
     */
    private String enforcePartyRoles(String html, DraftRequest request) {
        var config = contractRegistry.get(request.getTemplateId());
        if (config == null || config.partyRoles() == null || config.partyRoles().size() < 2) return html;

        String roleA = config.partyARole(); // e.g. "Licensor"
        String roleB = config.partyBRole(); // e.g. "Licensee"
        String nameA = nullToDefault(request.getPartyA(), roleA);
        String nameB = nullToDefault(request.getPartyB(), roleB);

        // Map generic terms → correct role. Only replace when surrounded by word boundaries.
        // PartyA variants
        String[][] partyAMap = {
            {"the Service Provider", roleA}, {"Service Provider", roleA},
            {"the Vendor", roleA}, {"Vendor", roleA},
            {"the Company", roleA}, {"Company", roleA},
            {"the Supplier", roleA}, {"Supplier", roleA},
            {"the Provider", roleA}, {"Provider", roleA},
            {"the Employer", roleA}, {"Employer", roleA},
        };
        // PartyB variants
        String[][] partyBMap = {
            {"the Client", roleB}, {"Client", roleB},
            {"the Enterprise", roleB}, {"Enterprise", roleB},
            {"the Buyer", roleB}, {"Buyer", roleB},
            {"the Customer", roleB}, {"Customer", roleB},
            {"the Employee", roleB}, {"Employee", roleB},
        };

        // Only replace variants that DON'T match the actual role
        for (String[] pair : partyAMap) {
            if (!pair[0].equalsIgnoreCase(roleA) && !pair[0].equalsIgnoreCase("the " + roleA)) {
                html = html.replace(pair[0], pair[1]);
            }
        }
        for (String[] pair : partyBMap) {
            if (!pair[0].equalsIgnoreCase(roleB) && !pair[0].equalsIgnoreCase("the " + roleB)) {
                html = html.replace(pair[0], pair[1]);
            }
        }

        return html;
    }

    // ── Missing terms detector ───────────────────────────────────────────────

    /**
     * After full draft generation, scans the complete HTML for missing deal terms.
     * Returns a list of terms from the deal brief that were NOT incorporated.
     */
    private List<String> detectMissingTerms(String fullHtml, DraftRequest request) {
        if (request.getExtractedDealTerms() == null) return List.of();
        String lower = fullHtml.toLowerCase();
        List<String> missing = new ArrayList<>();
        for (String termLine : request.getExtractedDealTerms().split("\n")) {
            String term = termLine.replaceFirst("^-\\s*", "").trim();
            if (term.isBlank()) continue;
            // Extract the value part after ":"
            String[] parts = term.split(":", 2);
            String value = parts.length > 1 ? parts[1].trim() : term;
            // Check if the key concept or value appears in the draft
            boolean found = lower.contains(value.toLowerCase());
            if (!found && parts.length > 1) {
                // Also check for the key
                found = lower.contains(parts[0].trim().toLowerCase());
            }
            if (!found) {
                missing.add(term);
            }
        }
        if (!missing.isEmpty()) {
            log.warn("Draft missing {} deal terms: {}", missing.size(), missing);
        }
        return missing;
    }

    // ── Input summary for draft HTML ─────────────────────────────────────────

    /**
     * Generates a "SCHEDULE — DRAFT PARAMETERS" section appended to the draft HTML.
     * Shows the user what input was used, enabling verification against the output.
     */
    private String buildInputSummaryHtml(DraftRequest request) {
        var config = contractRegistry.get(request.getTemplateId());
        String displayName = config != null ? config.displayName() : request.getTemplateId();
        String roleA = config != null ? config.partyARole() : "Party A";
        String roleB = config != null ? config.partyBRole() : "Party B";

        StringBuilder sb = new StringBuilder();
        // Collapsible section, visually distinct from the contract body
        sb.append("\n<hr style=\"margin-top:40px; border:none; border-top:2px dashed #ccc;\">\n");
        sb.append("<details style=\"margin-top:20px; background:#f8f9fa; border:1px solid #e0e0e0; border-radius:6px; padding:0;\">\n");
        sb.append("<summary style=\"cursor:pointer; padding:12px 16px; font-weight:bold; font-size:11pt; color:#555; user-select:none;\">");
        sb.append("Draft Generation Parameters (click to expand)</summary>\n");
        sb.append("<div style=\"padding:12px 16px; font-size:10pt; color:#666; line-height:1.5;\">\n");
        sb.append("<p style=\"margin:0 0 10px 0; font-style:italic; color:#999;\">")
          .append("This section shows the input used to generate this draft. Not part of the agreement.</p>\n");

        addParamItem(sb, "Contract Type", displayName);
        addParamItem(sb, roleA, nullToDefault(request.getPartyA(), "—"));
        if (request.getPartyAAddress() != null && !request.getPartyAAddress().isBlank()) {
            addParamItem(sb, roleA + " Address", request.getPartyAAddress());
        }
        addParamItem(sb, roleB, nullToDefault(request.getPartyB(), "—"));
        if (request.getPartyBAddress() != null && !request.getPartyBAddress().isBlank()) {
            addParamItem(sb, roleB + " Address", request.getPartyBAddress());
        }
        addParamItem(sb, "Jurisdiction", nullToDefault(request.getJurisdiction(), "—"));
        addParamItem(sb, "Practice Area", nullToDefault(request.getPracticeArea(), "—"));
        addParamItem(sb, "Counterparty Type", nullToDefault(request.getCounterpartyType(), "—"));

        String brief = request.getDealBrief() != null ? request.getDealBrief() : request.getDealContext();
        if (brief != null && !brief.isBlank()) {
            sb.append("<div style=\"margin-top:10px; padding:8px 12px; background:#fff; border:1px solid #eee; border-radius:4px;\">\n");
            sb.append("<strong style=\"color:#555;\">Deal Brief:</strong><br>\n");
            sb.append("<span style=\"color:#333;\">").append(brief).append("</span>\n");
            sb.append("</div>\n");
        }
        if (request.getExtractedDealTerms() != null && !request.getExtractedDealTerms().isBlank()) {
            sb.append("<div style=\"margin-top:10px; padding:8px 12px; background:#fff; border:1px solid #eee; border-radius:4px;\">\n");
            sb.append("<strong style=\"color:#555;\">Extracted Deal Terms:</strong><br>\n");
            String termsHtml = request.getExtractedDealTerms().replace("\n", "<br>");
            sb.append("<span style=\"color:#333;\">").append(termsHtml).append("</span>\n");
            sb.append("</div>\n");
        }

        sb.append("</div>\n</details>\n");
        return sb.toString();
    }

    private void addParamItem(StringBuilder sb, String label, String value) {
        sb.append("<p style=\"margin:3px 0;\"><strong style=\"color:#555;\">").append(label)
          .append(":</strong> <span style=\"color:#333;\">").append(value).append("</span></p>\n");
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

        // Cross-clause numerical consistency — notice periods, caps, currencies should
        // line up across the draft. Catch obvious contradictions at a mechanical level.
        issues.addAll(detectNumericalInconsistencies(sections, sectionValues));

        return issues;
    }

    /**
     * Mechanical consistency checks across clauses. Cheap, catches the worst
     * contradictions without needing an LLM call.
     */
    private List<CoherenceIssue> detectNumericalInconsistencies(
            List<String> sections, Map<String, String> sectionValues) {
        List<CoherenceIssue> issues = new ArrayList<>();

        // Collect "N days' notice" / "N (N) days" mentions from each clause
        java.util.regex.Pattern noticePattern = java.util.regex.Pattern.compile(
                "(\\d+)\\s*(?:\\(\\d+\\))?\\s*(?:business\\s+)?days['’]?\\s+(?:prior\\s+)?(?:written\\s+)?notice",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        Map<String, java.util.Set<String>> noticeByClause = new LinkedHashMap<>();
        for (String key : sections) {
            String html = sectionValues.getOrDefault(key, "");
            if (html.isBlank()) continue;
            String plain = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
            java.util.regex.Matcher m = noticePattern.matcher(plain);
            java.util.Set<String> periods = new java.util.LinkedHashSet<>();
            while (m.find()) periods.add(m.group(1));
            if (!periods.isEmpty()) noticeByClause.put(key, periods);
        }
        // Termination clause should usually share its notice period with any
        // referenced-from clauses. If TERMINATION says 30 days but SERVICES
        // references "60 days' notice", that's a contradiction worth flagging.
        if (noticeByClause.size() > 1) {
            java.util.Set<String> allPeriods = new java.util.LinkedHashSet<>();
            noticeByClause.values().forEach(allPeriods::addAll);
            if (allPeriods.size() > 2) {
                issues.add(new CoherenceIssue("CROSS_CLAUSE", "NOTICE_PERIOD_DRIFT",
                        "Multiple different notice periods cited across clauses: " + noticeByClause
                                + ". Verify these are intentionally distinct, not drift."));
            }
        }

        // Currency consistency — drafts shouldn't mix USD/INR/GBP unless the brief
        // specifies multi-currency. Count currency mentions per clause.
        java.util.regex.Pattern currencyPattern = java.util.regex.Pattern.compile(
                "\\b(USD|INR|GBP|EUR|CAD|AUD|SGD|\\$|₹|£|€)\\b|United States Dollar|Indian Rupee|Pound Sterling");
        java.util.Set<String> allCurrencies = new java.util.LinkedHashSet<>();
        for (String key : sections) {
            String html = sectionValues.getOrDefault(key, "");
            if (html.isBlank()) continue;
            String plain = html.replaceAll("<[^>]+>", " ");
            java.util.regex.Matcher m = currencyPattern.matcher(plain);
            while (m.find()) allCurrencies.add(m.group(0));
        }
        // Normalise currency symbols to canonical codes for dedup
        java.util.Set<String> normalisedCurrencies = allCurrencies.stream()
                .map(c -> switch (c) {
                    case "$" -> "USD";
                    case "₹" -> "INR";
                    case "£" -> "GBP";
                    case "€" -> "EUR";
                    case "United States Dollar" -> "USD";
                    case "Indian Rupee" -> "INR";
                    case "Pound Sterling" -> "GBP";
                    default -> c;
                })
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (normalisedCurrencies.size() > 1) {
            issues.add(new CoherenceIssue("CROSS_CLAUSE", "CURRENCY_MIXED",
                    "Draft mentions multiple currencies: " + normalisedCurrencies
                            + ". Confirm the contract is genuinely multi-currency."));
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
        m.put("AGREEMENT_REF", nullToDefault(r.getAgreementRef(), generateAgreementRef(r)));
        m.put("TERM_YEARS", nullToDefault(r.getTermYears(), "3"));
        m.put("NOTICE_DAYS", nullToDefault(r.getNoticeDays(), "30"));
        m.put("SURVIVAL_YEARS", nullToDefault(r.getSurvivalYears(), "5"));
        m.put("CONTRACT_TYPE_TITLE", resolveContractTypeName(r).toUpperCase());
        return m;
    }

    /** Auto-generate agreement reference: SLA-20260420-A1B2 */
    private String generateAgreementRef(DraftRequest r) {
        String prefix = switch (nullToDefault(r.getTemplateId(), "").toLowerCase()) {
            case "nda" -> "NDA";
            case "msa" -> "MSA";
            case "saas" -> "SAAS";
            case "software_license", "software-license" -> "SLA";
            case "ip_license", "ip-license" -> "IPL";
            case "employment" -> "EMP";
            case "supply" -> "SUP";
            default -> "AGR";
        };
        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        return prefix + "-" + date + "-" + suffix;
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

    // ── Deterministic template helpers for BLOCK enforcement ────────────

    /**
     * Apply deterministic fallback for unresolved BLOCK violations.
     * For each BLOCK rule that has an inject_template, inject the resolved text.
     * For BLOCK rules without templates, check if a matching deterministic template exists.
     */
    private String applyDeterministicFallback(String clauseHtml, String clauseType,
                                               List<ClauseRuleEngine.RuleResult> blockViolations,
                                               com.legalpartner.model.dto.DealSpec dealSpec) {
        String result = clauseHtml;
        String contractType = null; // will be resolved from current request context
        String jurisdiction = dealSpec != null && dealSpec.getLegal() != null
                ? dealSpec.getLegal().getJurisdiction() : null;
        String industry = null; // could be derived from DealSpec in future

        for (ClauseRuleEngine.RuleResult block : blockViolations) {
            boolean fixed = false;

            // Tier 1: Rule's own inject_template (highest priority — deal-specific)
            String injectTemplate = block.rule().injectTemplate();
            if (injectTemplate != null && !injectTemplate.isBlank()) {
                String resolved = resolveSimpleTemplate(injectTemplate, dealSpec);
                if (!resolved.contains("{{")) {
                    String injectionHtml = "\n<p class=\"clause-sub\">" + escapeHtmlText(resolved) + "</p>";
                    result = insertBeforeClosingTag(result, injectionHtml);
                    log.info("BLOCK fallback [{}]: Tier 1 — injected rule template for {}",
                            clauseType, block.rule().id());
                    fixed = true;
                }
            }

            // Tier 2: Deterministic templates from clause_requirements.yml
            if (!fixed) {
                List<ClauseRuleEngine.DeterministicTemplate> templates =
                        clauseRuleEngine.getDeterministicTemplates(clauseType, dealSpec);
                for (ClauseRuleEngine.DeterministicTemplate tmpl : templates) {
                    String resolved = resolveSimpleTemplate(tmpl.template(), dealSpec);
                    if (!resolved.contains("{{")) {
                        String injectionHtml = "\n<p class=\"clause-sub\">" + escapeHtmlText(resolved) + "</p>";
                        if ("PREPEND".equalsIgnoreCase(tmpl.position())) {
                            result = insertAfterOpeningTag(result, injectionHtml);
                        } else {
                            result = insertBeforeClosingTag(result, injectionHtml);
                        }
                        log.info("BLOCK fallback [{}]: Tier 2 — applied deterministic template {} for {}",
                                clauseType, tmpl.id(), block.rule().id());
                        fixed = true;
                        break;
                    }
                }
            }

            // Tier 3: Golden Clause Library (curated real clauses from precedent data)
            if (!fixed) {
                var goldenClause = goldenClauseLibrary.retrieve(
                        clauseType, contractType, jurisdiction, industry);
                if (goldenClause.isPresent()) {
                    String resolved = goldenClauseLibrary.resolve(goldenClause.get(), dealSpec, industry);
                    if (!resolved.isBlank()) {
                        // Wrap each line as a sub-clause paragraph
                        StringBuilder injectionHtml = new StringBuilder();
                        for (String line : resolved.split("\n")) {
                            if (line.isBlank()) continue;
                            injectionHtml.append("\n<p class=\"clause-sub\">")
                                    .append(escapeHtmlText(line.trim())).append("</p>");
                        }
                        result = insertBeforeClosingTag(result, injectionHtml.toString());
                        log.info("BLOCK fallback [{}]: Tier 3 — applied golden clause '{}' for {}",
                                clauseType, goldenClause.get().id(), block.rule().id());
                        fixed = true;
                    }
                }
            }

            if (!fixed) {
                log.warn("BLOCK fallback [{}]: no deterministic source for rule {} — violation unresolved",
                        clauseType, block.rule().id());
            }
        }

        return result;
    }

    /**
     * Apply deterministic templates to a clause — used for high-confidence structured values.
     * Templates are only applied if all placeholders resolve successfully.
     */
    private String applyDeterministicTemplates(String clauseHtml,
                                                List<ClauseRuleEngine.DeterministicTemplate> templates,
                                                com.legalpartner.model.dto.DealSpec dealSpec) {
        String result = clauseHtml;

        for (ClauseRuleEngine.DeterministicTemplate tmpl : templates) {
            String resolved = resolveSimpleTemplate(tmpl.template(), dealSpec);
            // Strip Handlebars-style {{#if ...}} / {{/if}} blocks for fields that are null
            resolved = stripConditionalBlocks(resolved, dealSpec);
            if (resolved.contains("{{")) {
                log.debug("Skipping deterministic template {}: unresolved placeholders", tmpl.id());
                continue;
            }

            // Check if the clause already contains the key content (avoid duplication)
            String plainResolved = resolved.replaceAll("\\s+", " ").trim().toLowerCase();
            String plainClause = clauseHtml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim().toLowerCase();
            if (plainClause.contains(plainResolved.substring(0, Math.min(60, plainResolved.length())))) {
                log.debug("Skipping deterministic template {}: content already present in clause", tmpl.id());
                continue;
            }

            String injectionHtml = "\n<p class=\"clause-sub\">" + escapeHtmlText(resolved) + "</p>";
            if ("PREPEND".equalsIgnoreCase(tmpl.position())) {
                result = insertAfterOpeningTag(result, injectionHtml);
            } else {
                result = insertBeforeClosingTag(result, injectionHtml);
            }
        }

        return result;
    }

    /**
     * Resolve {{field}} and {{field_formatted}} placeholders from DealSpec.
     */
    private String resolveSimpleTemplate(String template, com.legalpartner.model.dto.DealSpec dealSpec) {
        if (dealSpec == null || template == null) return template != null ? template : "";

        String result = template;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{([^#/}][^}]*)}}").matcher(template);
        while (m.find()) {
            String placeholder = m.group(1).trim();
            String replacement = null;

            if (placeholder.contains(":")) {
                // Cross-reference like {{article_number:IP_RIGHTS}} — leave as-is for now
                continue;
            }

            if (placeholder.endsWith("_formatted")) {
                String fieldPath = placeholder.replace("_formatted", "");
                replacement = dealSpec.resolveFieldFormatted(fieldPath);
            } else {
                Object val = dealSpec.resolveField(placeholder);
                if (val != null) replacement = val.toString();
            }

            if (replacement != null) {
                result = result.replace("{{" + placeholder + "}}", replacement);
            }
        }

        return result;
    }

    /**
     * Strip {{#if field}}...{{/if}} blocks where the field is null in DealSpec.
     * Keeps the content if the field is present and truthy.
     */
    private String stripConditionalBlocks(String text, com.legalpartner.model.dto.DealSpec dealSpec) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\{\\{#if\\s+([^}]+)}}(.*?)\\{\\{/if}}", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String fieldPath = m.group(1).trim();
            String content = m.group(2);
            Object val = dealSpec != null ? dealSpec.resolveField(fieldPath) : null;
            boolean truthy = val != null && !(val instanceof Boolean b && !b);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(truthy ? content : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Insert HTML before the last closing div/section/article tag. */
    private static String insertBeforeClosingTag(String html, String injection) {
        String lower = html.toLowerCase();
        for (String tag : List.of("</div>", "</section>", "</article>")) {
            int idx = lower.lastIndexOf(tag);
            if (idx >= 0) {
                return html.substring(0, idx) + injection + "\n" + html.substring(idx);
            }
        }
        return html + injection;
    }

    /** Insert HTML after the first opening div/section/article tag. */
    private static String insertAfterOpeningTag(String html, String injection) {
        String lower = html.toLowerCase();
        for (String tag : List.of("<div", "<section", "<article")) {
            int idx = lower.indexOf(tag);
            if (idx >= 0) {
                int closeIdx = html.indexOf('>', idx);
                if (closeIdx >= 0) {
                    return html.substring(0, closeIdx + 1) + injection + "\n" + html.substring(closeIdx + 1);
                }
            }
        }
        return injection + "\n" + html;
    }

    private static String escapeHtmlText(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
