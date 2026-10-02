package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.ClauseTypeRegistry;
import com.legalpartner.config.ClauseTypeRegistry.ClauseTypeConfig;
import com.legalpartner.config.ContractTypeRegistry;
import com.legalpartner.config.DenylistRegistry;
import com.legalpartner.config.IndustryRegulationRegistry;
import com.legalpartner.config.LegalSystemConfig;
import com.legalpartner.config.PartyNameVariantsConfig;
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
    private final DraftNormalizer draftNormalizer;
    private final IndustryRegulationRegistry industryRegulationRegistry;
    private final PartyNameVariantsConfig partyNameVariantsConfig;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final com.legalpartner.service.review.ClauseSpecRegistry clauseSpecRegistry;
    private final com.legalpartner.service.review.DraftVerifier draftVerifier;
    private final com.legalpartner.service.review.DraftManifestStore draftManifestStore;
    private final com.legalpartner.config.DraftingDefaults draftingDefaults;
    private final com.legalpartner.config.PromptRepository prompts;
    private final LlmOutputSanitizer outputSanitizer;
    private final com.legalpartner.service.learning.FirmClauseBankService firmClauseBank;
    private final com.legalpartner.service.learning.InsightService insightService;
    private final com.legalpartner.service.learning.ExposureService exposureService;
    private final com.legalpartner.service.learning.FirmNormsService firmNorms;
    private final com.legalpartner.service.review.PendingDraftStore pendingDrafts;
    private final String defaultJurisdiction;
    private final String storagePath;
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
                        DraftNormalizer draftNormalizer,
                        IndustryRegulationRegistry industryRegulationRegistry,
                        PartyNameVariantsConfig partyNameVariantsConfig,
                        org.springframework.context.ApplicationEventPublisher eventPublisher,
                        com.legalpartner.service.review.ClauseSpecRegistry clauseSpecRegistry,
                        com.legalpartner.service.review.DraftVerifier draftVerifier,
                        com.legalpartner.service.review.DraftManifestStore draftManifestStore,
                        com.legalpartner.config.DraftingDefaults draftingDefaults,
                        com.legalpartner.config.PromptRepository prompts,
                        LlmOutputSanitizer outputSanitizer,
                        com.legalpartner.service.learning.FirmClauseBankService firmClauseBank,
                        com.legalpartner.service.learning.InsightService insightService,
                        com.legalpartner.service.learning.ExposureService exposureService,
                        com.legalpartner.service.learning.FirmNormsService firmNorms,
                        com.legalpartner.service.review.PendingDraftStore pendingDrafts,
                        @Value("${legalpartner.draft.max-concurrent:2}") int maxConcurrent,
                        @Value("${legalpartner.defaults.jurisdiction:United States (Delaware)}") String defaultJurisdiction,
                        @Value("${legalpartner.storage.path:/data/documents}") String storagePath) {
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
        this.draftNormalizer = draftNormalizer;
        this.industryRegulationRegistry = industryRegulationRegistry;
        this.partyNameVariantsConfig = partyNameVariantsConfig;
        this.eventPublisher = eventPublisher;
        this.clauseSpecRegistry = clauseSpecRegistry;
        this.draftVerifier = draftVerifier;
        this.draftManifestStore = draftManifestStore;
        this.draftingDefaults = draftingDefaults;
        this.prompts = prompts;
        this.outputSanitizer = outputSanitizer;
        this.firmClauseBank = firmClauseBank;
        this.insightService = insightService;
        this.exposureService = exposureService;
        this.firmNorms = firmNorms;
        this.pendingDrafts = pendingDrafts;
        this.fileStorageService = fileStorageService;
        this.defaultJurisdiction = defaultJurisdiction;
        this.storagePath = storagePath;
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
            return doGenerateDraft(request, username);
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
            try { doStreamDraft(request, emitter, username); }
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

    // ── Shared drafting pipeline (async, streaming and sync drafts) ───────────────

    /** Progress callbacks: each entry point reports in its own way (document row, SSE, nothing). */
    private interface PipelineListener {
        default void planned(List<String> sections, String skeletonHtml) {}
        default void clauseStarted(int index, String key, String title) {}
        default void clauseDone(int index, String key, String title, ClauseResult result, String partialHtml) {}
    }

    /** Everything one drafting run produced. */
    private record PipelineResult(
            List<String> plannedSections, String fullHtml, String parametersHtml,
            Map<String, List<String>> qaWarnings, List<String> coherenceIssues, List<ClauseSuggestion> suggestions,
            com.legalpartner.service.review.DraftManifest draftManifest, Map<String, ClauseOrigin> origins) {}

    /** One drafted section and the precedent documents the LLM saw for it (empty for firm/golden clauses). */
    private record SectionDraft(ClauseResult result, List<String> sources) {}

    /**
     * The drafting pipeline every entry point runs: deal spec → section plan → per section
     * (approved firm clause → golden clause → LLM, then rule engine, deterministic templates
     * and review-rubric verification) → coherence scan → normalization → draft manifest.
     */
    private PipelineResult runDraftPipeline(DraftRequest request, PipelineListener listener, SseEmitter emitter) {
        final com.legalpartner.model.dto.DealSpec finalDealSpec = extractDealSpec(request);

        String[] templateParts = loadTemplateParts(request.getTemplateId(), buildPlaceholderMap(request));
        List<String> plannedSections = planSections(request);
        log.info("Draft pipeline: section planner chose {} sections: {}", plannedSections.size(), plannedSections);

        Map<String, String> sectionValues = new LinkedHashMap<>();
        for (String key : plannedSections) {
            ClauseTypeConfig spec = clauseRegistry.get(key);
            sectionValues.put(key, "<em style='color:#9CA3AF'>&#x23F3; Generating " + spec.title() + " clause…</em>");
        }
        listener.planned(plannedSections, buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues));

        TerminologyManifest manifest = buildInitialManifest(request, plannedSections);
        Map<String, List<String>> allQaWarnings = new LinkedHashMap<>();
        Map<String, List<ClauseRuleEngine.RuleResult>> allRuleResults = new LinkedHashMap<>();
        List<FixEngine.AuditEntry> allAuditEntries = new ArrayList<>();
        Map<String, ClauseOrigin> origins = new LinkedHashMap<>();
        List<ClauseSuggestion> suggestions = new ArrayList<>();

        for (int i = 0; i < plannedSections.size(); i++) {
            String key = plannedSections.get(i);
            ClauseTypeConfig spec = clauseRegistry.get(key);
            listener.clauseStarted(i, key, spec.title());

            SectionDraft drafted = draftSection(key, spec, request, finalDealSpec, manifest, origins,
                    allRuleResults, allAuditEntries, emitter);
            ClauseResult result = drafted.result();

            sectionValues.put(key, result.html());
            if ("DEFINITIONS".equals(key)) manifest = manifest.withDefinedTerms(extractDefinedTerms(result.html()));
            manifest = manifest.withAppendedClause(
                    summarizeClauseOutline(i + 1, spec.title(), result.html()),
                    stripToPlainWithCap(result.html(), 1500));
            if (!result.qaWarnings().isEmpty()) allQaWarnings.put(key, result.qaWarnings());
            suggestions.add(suggestionFor(spec, origins.get(key), drafted.sources()));

            listener.clauseDone(i, key, spec.title(), result,
                    buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues));
        }

        List<String> coherenceSummary = runCoherenceScan(plannedSections, sectionValues, manifest, request.getTemplateId())
                .stream().map(ci -> "[" + ci.clause() + "] " + ci.type() + ": " + ci.detail()).collect(Collectors.toList());
        if (!coherenceSummary.isEmpty()) log.warn("Coherence scan found {} issue(s)", coherenceSummary.size());

        // Final normalization pass (dedup, meta-strip, numbering, cross-refs, amounts)
        sectionValues = draftNormalizer.normalize(sectionValues, finalDealSpec);
        String fullHtml = buildDynamicHtml(templateParts[0], templateParts[1], plannedSections, sectionValues);
        if (finalDealSpec != null) fullHtml = stripHallucinatedAmounts(fullHtml, finalDealSpec);

        List<String> missingTerms = detectMissingTerms(fullHtml, request);
        if (!missingTerms.isEmpty()) {
            log.warn("Draft: {} deal terms not found in output: {}", missingTerms.size(), missingTerms);
        }
        if (finalDealSpec != null && !allRuleResults.isEmpty()) {
            DealCoverageScore.CoverageReport coverage = dealCoverageScore.compute(
                    allRuleResults, allAuditEntries, finalDealSpec);
            log.info("Draft: coverage={}, risk={}, blockers={}, fixes={}",
                    String.format("%.0f%%", coverage.overallCoverage() * 100),
                    coverage.overallRisk(), coverage.blockers().size(), coverage.fixesApplied().size());
        }

        return new PipelineResult(plannedSections, fullHtml, buildInputSummaryHtml(request), allQaWarnings,
                coherenceSummary, suggestions, buildManifest(request, plannedSections, sectionValues, finalDealSpec), origins);
    }

    /** Draft one section: approved firm clause → golden clause → LLM, then rules and rubric verification. */
    private SectionDraft draftSection(String key, ClauseTypeConfig spec, DraftRequest request,
                                      com.legalpartner.model.dto.DealSpec finalDealSpec, TerminologyManifest manifest,
                                      Map<String, ClauseOrigin> origins,
                                      Map<String, List<ClauseRuleEngine.RuleResult>> allRuleResults,
                                      List<FixEngine.AuditEntry> allAuditEntries, SseEmitter emitter) {
        List<String> sources = new ArrayList<>();
        // ── Firm-first: a lawyer-approved clause from this firm's own contracts ──
        ClauseResult result = null;
        FirmPick firmPick = firmClauseFor(key, request);
        if (firmPick != null) {
            result = firmPick.result();
            origins.put(key, firmPick.origin());
        }

        // ── Deterministic-first: use golden clause if available for critical clauses ──
        // golden_first (clauses.yml): render deterministically when golden clauses exist.
        if (result == null && spec.goldenFirst() && finalDealSpec != null) {
            var goldenClauses = goldenClauseLibrary.retrieveAll(
                    key, request.getTemplateId(),
                    finalDealSpec.getLegal() != null ? finalDealSpec.getLegal().getJurisdiction() : null,
                    request.getIndustry());
            if (!goldenClauses.isEmpty()) {
                StringBuilder goldenHtml = new StringBuilder();
                for (var gc : goldenClauses) {
                    String resolved = goldenClauseLibrary.resolve(gc, finalDealSpec, request.getIndustry());
                    if (!resolved.isBlank()) goldenHtml.append(GoldenClauseLibrary.toHtml(resolved));
                }
                if (goldenHtml.length() > 100) {
                    result = new ClauseResult(goldenHtml.toString(), List.of());
                    origins.put(key, new ClauseOrigin(com.legalpartner.service.learning.ExposureService.GOLDEN, null, List.of()));
                    log.info("Deterministic-first [{}]: rendered from {} golden clause(s), skipping LLM",
                            key, goldenClauses.size());
                }
            }
        }

        // ── Fallback to LLM generation if no golden clause available ──
        if (result == null) {
            origins.put(key, new ClauseOrigin(com.legalpartner.service.learning.ExposureService.LLM, null,
                    insightsFor(key, request).stream().map(com.legalpartner.model.entity.learning.DraftingInsight::getId).toList()));
            DraftContext ctx = draftContextRetriever.retrieveForClause(key, request);
            sources.addAll(ctx.sourceDocuments());
            result = generateClauseWithQa(
                    request, ctx, key, spec.systemPrompt(), spec.userPromptTemplate(),
                    spec.expectedSubclauses(), emitter, manifest);
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

        // ── Review-rubric verification: same questions + evaluator review will use ──
        result = verifyAgainstReviewRubric(key, result, request, finalDealSpec);

        return new SectionDraft(result, sources);
    }

    private static ClauseSuggestion suggestionFor(ClauseTypeConfig spec, ClauseOrigin origin, List<String> sources) {
        String provenance = origin == null ? com.legalpartner.service.learning.ExposureService.LLM : origin.provenance();
        String reasoning;
        if (com.legalpartner.service.learning.ExposureService.FIRM_BANK.equals(provenance)) {
            reasoning = "Approved firm clause from your firm's own contracts.";
        } else if (com.legalpartner.service.learning.ExposureService.GOLDEN.equals(provenance)) {
            reasoning = "Rendered from the golden clause library with this deal's terms.";
        } else {
            reasoning = "Generated using RAG from firm's corpus.";
            if (!sources.isEmpty()) reasoning += " Sources: " + String.join(", ", sources.subList(0, Math.min(3, sources.size())));
        }
        return ClauseSuggestion.builder()
                .clauseRef(spec.title() + " clause")
                .currentText("(" + provenance + ")")
                .suggestion("Review and customize for your specific matter and client.")
                .reasoning(reasoning)
                .build();
    }

    /** Structured deal terms from the brief, overlaid with form fields and firm-norm defaults; null without a brief. */
    private com.legalpartner.model.dto.DealSpec extractDealSpec(DraftRequest request) {
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
            // Overlay form fields onto DealSpec — form values take priority over brief extraction
            if (request.getJurisdiction() != null && !request.getJurisdiction().isBlank()) {
                if (dealSpec.getLegal() == null) dealSpec.setLegal(new com.legalpartner.model.dto.DealSpec.LegalTerms());
                dealSpec.getLegal().setJurisdiction(request.getJurisdiction());
            }
            if (request.getNoticeDays() != null && !request.getNoticeDays().isBlank()) {
                if (dealSpec.getLegal() == null) dealSpec.setLegal(new com.legalpartner.model.dto.DealSpec.LegalTerms());
                try { dealSpec.getLegal().setNoticeDays(Integer.parseInt(request.getNoticeDays())); } catch (Exception ignored) {}
            }
            // Firm norms: values the brief and form left empty default to what this firm usually signs.
            try {
                var applied = firmNorms.applyDraftingDefaults(dealSpec, learningDocumentType(request));
                if (!applied.isEmpty()) log.info("DealSpec: firm-norm defaults applied {}", applied);
            } catch (Exception e) {
                log.warn("DealSpec: firm-norm defaults skipped: {}", e.getMessage());
            }
            log.info("DealSpec extracted: partyA={}, partyB={}, jurisdiction={}, fees={}, license={}",
                    dealSpec.getPartyA() != null ? dealSpec.getPartyA().getName() : "?",
                    dealSpec.getPartyB() != null ? dealSpec.getPartyB().getName() : "?",
                    dealSpec.getLegal() != null ? dealSpec.getLegal().getJurisdiction() : "?",
                    dealSpec.getFees() != null ? dealSpec.getFees().getLicenseFee() : "?",
                    dealSpec.getLicense() != null ? dealSpec.getLicense().getType() : "?");
        }
        // Keep old extraction as fallback
        hydratePartiesFromDealBrief(request);

        return dealSpec;
    }

    private void doAsyncDraft(UUID docId, DraftRequest request, String username) throws Exception {
        hydrateFromMatter(request);
        DocumentMetadata doc0 = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalStateException("Async draft row " + docId + " vanished"));
        doc0.setProcessingStatus(ProcessingStatus.PROCESSING);
        doc0.setLastProgressAt(Instant.now());
        doc0.setCurrentClauseLabel("Planning sections");
        final DocumentMetadata[] docRef = {documentRepository.save(doc0)};

        PipelineResult r = runDraftPipeline(request, new PipelineListener() {
            @Override public void planned(List<String> sections, String skeletonHtml) {
                DocumentMetadata d = docRef[0];
                d.setTotalClauses(sections.size());
                d.setCompletedClauses(0);
                d.setLastProgressAt(Instant.now());
                storeHtml(d, skeletonHtml);
                docRef[0] = documentRepository.save(d);
            }
            @Override public void clauseStarted(int index, String key, String title) {
                DocumentMetadata d = docRef[0];
                d.setCurrentClauseLabel(title + " (" + (index + 1) + "/" + d.getTotalClauses() + ")");
                d.setLastProgressAt(Instant.now());
                docRef[0] = documentRepository.save(d);
            }
            @Override public void clauseDone(int index, String key, String title, ClauseResult result, String partialHtml) {
                DocumentMetadata d = docRef[0];
                d.setCompletedClauses(index + 1);
                d.setLastProgressAt(Instant.now());
                storeHtml(d, partialHtml);
                docRef[0] = documentRepository.save(d);
            }
        }, null);
        DocumentMetadata doc = docRef[0];

        // Input summary is stored separately (not inside the contract HTML/DOCX)
        storeParametersHtml(doc, r.parametersHtml());
        storeHtml(doc, r.fullHtml());

        // Draft → review handoff (exact sections) and the learning loop's exposure log.
        draftManifestStore.write(docId, r.draftManifest());
        exposureService.record(docId, request.getTemplateId(), learningTypeName(request),
                buildExposures(r.draftManifest(), r.origins()));

        // Also generate DOCX version for Word/OnlyOffice editing
        try {
            byte[] docxBytes = htmlToDocxConverter.convert(r.fullHtml());
            String docxPath = storagePath + "/" + docId + ".docx";
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
                 docId, r.plannedSections().size(), r.qaWarnings().size());

        // Publish event for proactive agent triggers
        try {
            eventPublisher.publishEvent(new com.legalpartner.event.DraftCompletedEvent(
                    docId, doc.getUploadedBy(), doc.getDocumentType() != null ? doc.getDocumentType().name() : "UNKNOWN"));
        } catch (Exception e) {
            log.warn("Failed to publish DraftCompletedEvent for {}: {}", docId, e.getMessage());
        }
    }

    // ── Learning loop (docs/LEARNING_LOOP_ARCHITECTURE.md) ─────────────────────

    /** Where a drafted clause came from — logged as its exposure. */
    private record ClauseOrigin(String provenance, UUID firmClauseId, List<UUID> insightIds) {}

    private record FirmPick(ClauseResult result, ClauseOrigin origin) {}

    /** DocumentType name a template's drafts are learned under (same key as firm documents). */
    private com.legalpartner.model.enums.DocumentType learningDocumentType(DraftRequest request) {
        String t = contractRegistry.documentType(request.getTemplateId());
        if (t == null) return null;
        try { return com.legalpartner.model.enums.DocumentType.valueOf(t); } catch (IllegalArgumentException e) { return null; }
    }

    private List<com.legalpartner.model.entity.learning.DraftingInsight> insightsFor(String clauseKey, DraftRequest request) {
        var type = learningDocumentType(request);
        if (type == null) return List.of();
        try {
            return insightService.activeFor(clauseKey, type.name());
        } catch (Exception e) {
            log.warn("Learned insights for [{}] skipped: {}", clauseKey, e.getMessage());
            return List.of();
        }
    }

    /** Approved firm clause for this section and stance, rendered for this deal; null when none. */
    private FirmPick firmClauseFor(String clauseKey, DraftRequest request) {
        var type = learningDocumentType(request);
        if (type == null) return null;
        try {
            var sel = firmClauseBank.selectForDraft(clauseKey, type.name(), request.getDraftStance());
            if (sel.isEmpty()) return null;
            String html = GoldenClauseLibrary.toHtml(
                    firmClauseBank.render(sel.get().text(), request.getPartyA(), request.getPartyB()));
            if (html.isBlank()) return null;
            firmClauseBank.recordUse(sel.get().firmClauseId());
            log.info("Firm-first [{}]: approved firm clause {} used", clauseKey, sel.get().firmClauseId());
            return new FirmPick(new ClauseResult(html, List.of()),
                    new ClauseOrigin(com.legalpartner.service.learning.ExposureService.FIRM_BANK, sel.get().firmClauseId(), List.of()));
        } catch (Exception e) {
            log.warn("Firm clause for [{}] skipped: {}", clauseKey, e.getMessage());
            return null;
        }
    }

    /** Exposure log entries: the final text of each article and what it was built from. */
    private List<com.legalpartner.service.learning.ExposureService.Exposure> buildExposures(
            com.legalpartner.service.review.DraftManifest manifest, Map<String, ClauseOrigin> origins) {
        List<com.legalpartner.service.learning.ExposureService.Exposure> list = new ArrayList<>();
        for (var sec : manifest.sections()) {
            ClauseOrigin o = origins.getOrDefault(sec.key(),
                    new ClauseOrigin(com.legalpartner.service.learning.ExposureService.LLM, null, List.of()));
            list.add(new com.legalpartner.service.learning.ExposureService.Exposure(
                    sec.key(), sec.article(), sec.title(), o.provenance(), o.firmClauseId(), o.insightIds(), sec.text()));
        }
        return list;
    }

    private String learningTypeName(DraftRequest request) {
        var t = learningDocumentType(request);
        return t != null ? t.name() : null;
    }

    /**
     * Verify a finished clause against the review rubric and repair once if needed.
     * Residual unmet requirements become QA warnings so the lawyer sees them.
     */
    private ClauseResult verifyAgainstReviewRubric(String key, ClauseResult result, DraftRequest request,
                                                   com.legalpartner.model.dto.DealSpec dealSpec) {
        if (!draftVerifier.isEnabled()) return result;
        try {
            var outcome = draftVerifier.verifyAndRepair(
                    key, result.html(), resolveContractTypeName(request),
                    clauseSpecRegistry.reviewType(request.getTemplateId()),
                    request.getClientPosition(), dealSpec,
                    raw -> sanitizeClauseText(stripLlmArtifacts(raw)));
            if (outcome.warnings().isEmpty() && !outcome.repaired()) return result;
            List<String> warnings = new ArrayList<>(result.qaWarnings());
            warnings.addAll(outcome.warnings());
            return new ClauseResult(outcome.html(), warnings);
        } catch (Exception e) {
            log.warn("Draft verify [{}] skipped: {}", key, e.getMessage());
            return result;
        }
    }

    /** Structured record of the draft for review — see {@link com.legalpartner.service.review.DraftManifest}. */
    private com.legalpartner.service.review.DraftManifest buildManifest(
            DraftRequest request, List<String> plannedSections, Map<String, String> sectionValues,
            com.legalpartner.model.dto.DealSpec dealSpec) {
        List<com.legalpartner.service.review.DraftManifest.Section> sections = new ArrayList<>();
        for (int i = 0; i < plannedSections.size(); i++) {
            String key = plannedSections.get(i);
            String html = sectionValues.getOrDefault(key, "");
            if (dealSpec != null) html = stripHallucinatedAmounts(html, dealSpec);
            sections.add(new com.legalpartner.service.review.DraftManifest.Section(
                    key, clauseRegistry.get(key).title(), i + 1,
                    clauseSpecRegistry.reviewKeysFor(key),
                    com.legalpartner.rag.HtmlText.toPlainText(html)));
        }
        String jurisdiction = dealSpec != null && dealSpec.getLegal() != null && dealSpec.getLegal().getJurisdiction() != null
                ? dealSpec.getLegal().getJurisdiction() : request.getJurisdiction();
        return new com.legalpartner.service.review.DraftManifest(
                com.legalpartner.service.review.DraftManifest.CURRENT_VERSION,
                request.getTemplateId(),
                clauseSpecRegistry.reviewType(request.getTemplateId()),
                request.getClientPosition(),
                request.getDraftStance(),
                jurisdiction,
                sections,
                request.getDealBrief() != null ? request.getDealBrief() : request.getDealContext());
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

    /** Stores draft parameters HTML as a separate file alongside the draft. */
    private void storeParametersHtml(DocumentMetadata doc, String parametersHtml) {
        try {
            // Store with a distinct path: <storageDir>/<docId>_params.html
            // Using direct file write since FileStorageService.store derives name from docId+ext only.
            String mainPath = doc.getStoredPath();
            if (mainPath == null) {
                // Draft HTML not yet stored — derive the path from conventions
                mainPath = storagePath + "/" + doc.getId() + ".html";
            }
            String paramsPath = mainPath.replace(".html", "_params.html");
            java.nio.file.Files.write(java.nio.file.Path.of(paramsPath), parametersHtml.getBytes());
        } catch (IOException e) {
            log.warn("Async draft {}: parameters HTML store failed — {}", doc.getId(), e.getMessage());
        }
    }

    /** Reads the separately-stored parameters HTML for a draft, or returns null. */
    public String readParametersHtml(DocumentMetadata doc) {
        if (doc.getStoredPath() == null) return null;
        String paramsPath = doc.getStoredPath().replace(".html", "_params.html");
        try {
            java.nio.file.Path p = java.nio.file.Path.of(paramsPath);
            if (java.nio.file.Files.exists(p)) {
                return new String(java.nio.file.Files.readAllBytes(p));
            }
        } catch (IOException e) {
            log.debug("Could not read parameters HTML for draft {}: {}", doc.getId(), e.getMessage());
        }
        return null;
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

    private DraftResponse doGenerateDraft(DraftRequest request, String username) {
        PipelineResult r = runDraftPipeline(request, new PipelineListener() {}, null);
        return DraftResponse.builder()
                .draftHtml(r.fullHtml())
                .draftParametersHtml(r.parametersHtml())
                .suggestions(r.suggestions())
                .qaWarnings(r.qaWarnings().isEmpty() ? null : r.qaWarnings())
                .coherenceIssues(r.coherenceIssues().isEmpty() ? null : r.coherenceIssues())
                .draftToken(holdForSave(request, r, username))
                .build();
    }

    private void doStreamDraft(DraftRequest request, SseEmitter emitter, String username) throws Exception {
        emitter.send(SseEmitter.event().data(toJson(Map.of("type", "planning"))));

        PipelineResult r = runDraftPipeline(request, new PipelineListener() {
            private int total;
            @Override public void planned(List<String> sections, String skeletonHtml) {
                total = sections.size();
                send(emitter, Map.of("type", "start", "totalClauses", total,
                        "plannedSections", sections, "partialHtml", skeletonHtml));
            }
            @Override public void clauseStarted(int index, String key, String title) {
                send(emitter, Map.of("type", "clause_start", "clauseType", key, "label", title,
                        "index", index + 1, "totalClauses", total));
            }
            @Override public void clauseDone(int index, String key, String title, ClauseResult result, String partialHtml) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "clause_done");
                payload.put("clauseType", key);
                payload.put("label", title);
                payload.put("index", index + 1);
                payload.put("totalClauses", total);
                payload.put("qaWarnings", result.qaWarnings());
                payload.put("partialHtml", partialHtml);
                send(emitter, payload);
            }
        }, emitter);

        Map<String, Object> completePayload = new LinkedHashMap<>();
        completePayload.put("type", "complete");
        completePayload.put("draftHtml", r.fullHtml());
        completePayload.put("draftParametersHtml", r.parametersHtml());
        completePayload.put("suggestions", r.suggestions());
        completePayload.put("qaWarnings", r.qaWarnings());
        if (!r.coherenceIssues().isEmpty()) completePayload.put("coherenceIssues", r.coherenceIssues());
        completePayload.put("draftToken", holdForSave(request, r, username));
        emitter.send(SseEmitter.event().data(toJson(completePayload)));
        emitter.complete();
    }

    private void send(SseEmitter emitter, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().data(toJson(payload)));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Keep the manifest + exposures of an unsaved draft until it is saved (see PendingDraftStore). */
    private String holdForSave(DraftRequest request, PipelineResult r, String username) {
        try {
            return pendingDrafts.put(new com.legalpartner.service.review.PendingDraftStore.PendingDraft(
                    username, request.getTemplateId(), learningTypeName(request), r.draftManifest(),
                    buildExposures(r.draftManifest(), r.origins()), normalizedPlain(r.fullHtml()), Instant.now()));
        } catch (Exception e) {
            log.warn("Draft handoff token not created: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Attach a saved sync/stream draft's manifest and exposure log to its new document.
     * The manifest is only attached when the saved HTML is the generated draft unchanged —
     * otherwise review reads the saved text. Exposures always record the AI's original text.
     */
    public void attachSavedDraft(String draftToken, UUID docId, String savedHtml, String username) {
        pendingDrafts.take(draftToken, username).ifPresent(p -> {
            boolean unchanged = p.generatedPlainText().equals(normalizedPlain(savedHtml));
            if (unchanged) draftManifestStore.write(docId, p.manifest());
            exposureService.record(docId, p.templateId(), p.contractType(), p.exposures());
            log.info("Saved draft {}: manifest {}, {} exposure(s) attached", docId,
                    unchanged ? "attached" : "skipped (edited before save)", p.exposures().size());
        });
    }

    private static String normalizedPlain(String html) {
        return com.legalpartner.rag.HtmlText.toPlainText(html == null ? "" : html).replaceAll("\\s+", " ").trim();
    }

    // ── Section planner ────────────────────────────────────────────────────────

    /**
     * Ask the LLM which sections this contract should contain, in order.
     * Returns a list of keys from CLAUSE_SPECS. Falls back to defaults if parsing fails.
     */
    private List<String> planSections(DraftRequest request) {
        String prompt = String.format(PromptTemplates.SECTION_PLANNER_USER,
                resolveContractTypeName(request),
                nullToDefault(request.getPartyA(), defaultPartyA(request)),
                nullToDefault(request.getPartyB(), defaultPartyB(request)),
                nullToDefault(request.getPracticeArea(), draftingDefaults.prompt("practice_area")),
                nullToDefault(request.getIndustry(), draftingDefaults.prompt("industry")),
                nullToDefault(request.getDealBrief(), draftingDefaults.prompt("deal_brief")));

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

    /** legalpartner.draft.qa.max-retries — QA-driven regeneration attempts per clause. */
    @Value("${legalpartner.draft.qa.max-retries:2}")
    private int qaMaxRetries = 2;

    /** legalpartner.draft.qa.subclause-overage — extra sub-clauses tolerated before truncation. */
    @Value("${legalpartner.draft.qa.subclause-overage:2}")
    private int subclauseOverage = 2;

    /**
     * Generates a clause and auto-retries up to qaMaxRetries times if the QA pass
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
                nullToDefault(request.getPartyA(), defaultPartyA(request)),
                nullToDefault(request.getPartyB(), defaultPartyB(request)),
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

    /** Style directive from drafting_defaults.yml; register from jurisdictions.yml (DRAFTING_REGISTER). */
    private String buildStyleFingerprint(DraftRequest request) {
        String jurisdiction = nullToDefault(request.getJurisdiction(), defaultJurisdiction);
        String register = legalSystemConfig.localizeForJurisdiction("%DRAFTING_REGISTER%", jurisdiction);
        return draftingDefaults.styleFingerprint(Map.of("DRAFTING_REGISTER", register));
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

    private String buildManifestConstraint(TerminologyManifest manifest, String templateId) {
        java.util.Set<String> ownRoles = new java.util.HashSet<>();
        contractRegistry.partyRoles(templateId).forEach(r -> ownRoles.add(r.toLowerCase()));
        StringBuilder sb = new StringBuilder(String.format(prompts.get("DRAFT_TERMINOLOGY_MANDATE"),
                manifest.partyAName(), manifest.partyBName(),
                contractRegistry.get(templateId).partyARole(), contractRegistry.get(templateId).partyBRole(),
                String.join(", ", driftSynonyms(partyNameVariantsConfig.partyAVariants(), ownRoles)),
                String.join(", ", driftSynonyms(partyNameVariantsConfig.partyBVariants(), ownRoles))));
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
        String jurisdiction = nullToDefault(request.getJurisdiction(), defaultJurisdiction);
        String counterparty = nullToDefault(request.getCounterpartyType(), draftingDefaults.prompt("counterparty_type"));
        String practiceArea = nullToDefault(request.getPracticeArea(), draftingDefaults.prompt("practice_area"));
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
        String manifestConstraint = (manifest != null) ? buildManifestConstraint(manifest, request.getTemplateId()) : "";
        String ragGrounding = ctx.chunkCount() > 0 ? prompts.get("DRAFT_RAG_GROUNDING") : "";
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

        // Review checklist: the semantic requirements this clause will be reviewed against.
        String reviewChecklist = "";
        if (draftVerifier.isEnabled()) {
            reviewChecklist = clauseSpecRegistry.requirementsPrompt(clauseSpecRegistry.semanticRequirements(
                    clauseKey, clauseSpecRegistry.reviewType(request.getTemplateId()),
                    request.getClientPosition(), draftVerifier.minWeight()));
        }

        // Learned firm preferences go with the clause-specific system prompt so retries keep them.
        String localizedSystemPrompt = legalSystemConfig.localizeForJurisdiction(systemPrompt, jurisdiction)
                + insightService.promptBlock(insightsFor(clauseKey, request));
        String fullSystemAndInitial = PromptTemplates.DRAFT_CONTENT_GUARDRAILS
                + manifestConstraint
                + ragGrounding
                + scratchpadConstraint
                + dealRequirements
                + reviewChecklist
                + "\n\n" + localizedSystemPrompt
                + "\n\n" + initialPrompt;

        // Generate initial attempt
        String generated = sanitizeClauseText(stripLlmArtifacts(
                chatModel.generate(UserMessage.from(fullSystemAndInitial)).content().text().trim()));
        // Truncate excess sub-clauses — model sometimes generates 10+ when spec says 3
        if (expectedSubclauses > 0 && countSubClauses(generated) > expectedSubclauses + subclauseOverage) {
            log.warn("Clause [{}]: {} sub-clauses generated, truncating to {}", clauseKey,
                    countSubClauses(generated), expectedSubclauses + 1);
            generated = truncateToNSubClauses(generated, expectedSubclauses + 1);
        }
        List<String> qaWarnings = qaClause(clauseKey, generated, expectedSubclauses, contractType);

        // Best-of-N tracking — keep the highest-scoring attempt across retries
        String bestGenerated = generated;
        List<String> bestWarnings = qaWarnings;
        int bestScore = scoreAttempt(generated, qaWarnings);

        for (int attempt = 1; attempt <= qaMaxRetries && !qaWarnings.isEmpty(); attempt++) {
            log.warn("QA [{}] attempt {}/{}: {} issues — retrying (current best score: {})",
                    clauseKey, attempt, qaMaxRetries, qaWarnings.size(), bestScore);

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
            log.warn("QA [{}]: {} residual warning(s) after {} retries — applying post-processor", clauseKey, qaWarnings.size(), qaMaxRetries);
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
        StringBuilder issues = new StringBuilder();
        for (String w : warnings) issues.append("  - ").append(w).append("\n");
        return String.format(prompts.get("DRAFT_ISOLATED_RETRY"),
                systemPrompt, originalUserPrompt, issues, clauseKey, expectedSubclauses);
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

        // Focused prompt asking only for the missing sub-clauses
        String prompt = String.format(prompts.get("DRAFT_MISSING_SUBCLAUSES"),
                systemPrompt, originalUserPrompt, existing, existing + 1, expectedSubclauses);

        String additionalText = sanitizeClauseText(stripLlmArtifacts(
                chatModel.generate(UserMessage.from(prompt)).content().text().trim()));

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

        // 5. LLM artifacts that survived cleanup (output_cleanup.yml qa_artifact_checks)
        for (String msg : outputSanitizer.artifactWarnings(plain, contractType)) {
            warnings.add(msg);
            log.warn("QA [{}]: {}", clauseKey, msg);
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


    /**
     * Semantic requirements per clause type: keywords that MUST appear in the generated text.
     * These represent the minimum legally meaningful content each clause type must address.
     * If absent, QA flags with a targeted warning so the retry knows exactly what to add.
     */
    // CLAUSE_SEMANTIC_REQUIREMENTS now lives in clauses.yml (semantic_requirements
    // per clause). Access via clauseRegistry.get(key).semanticRequirements().

    /**
     * Terms from contract_types.yml {@code banned_terms} for the contract being drafted
     * (matched by display name) that appear in the clause as whole words.
     */
    private List<String> detectContamination(String plain, String contractType) {
        List<String> banned = contractRegistry.findByDisplayName(contractType)
                .map(ContractTypeRegistry.ContractTypeConfig::bannedTerms).orElse(List.of());
        List<String> found = new ArrayList<>();
        for (String term : banned) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?i)(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(term) + "(?![\\p{L}\\p{N}])");
            if (p.matcher(plain).find() && !found.contains(term)) found.add(term);
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
            String extractPrompt = prompts.get("DRAFT_PARTY_EXTRACTION") + brief;
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
            sb.append(String.format(prompts.get("DRAFT_DEAL_TERMS_BLOCK"), extractedTerms));
        }

        String position = request.getClientPosition();
        if (position != null && !position.isBlank()) {
            String posLabel = labelPrompt("DRAFT_POSITION_", position, "NEUTRAL");
            sb.append("Client position: ").append(posLabel).append("\n");
        }

        String industry = request.getIndustry();
        if (industry != null && !industry.isBlank()) {
            String jur = request.getJurisdiction() != null ? request.getJurisdiction() : "";
            String regRef = industryRegulationRegistry.getRegulatoryReference(jur, industry);
            if (!regRef.isEmpty()) {
                sb.append("Industry: ").append(industry).append(". ").append(regRef).append("\n");
            }
        }

        String stance = request.getDraftStance();
        if (stance != null && !stance.isBlank()) {
            String stanceLabel = labelPrompt("DRAFT_STANCE_", stance, "BALANCED");
            sb.append("Drafting stance: ").append(stanceLabel).append("\n");
        }

        return sb.length() == 0 ? "" : sb.toString();
    }

    /** Prompt text for a request value (e.g. DRAFT_STANCE_FINAL_OFFER), falling back to the default key. */
    private String labelPrompt(String prefix, String value, String fallback) {
        String id = prefix + value.trim().toUpperCase();
        return prompts.contains(id) ? prompts.get(id) : prompts.get(prefix + fallback);
    }

    // ── LLM artifact stripper — runs BEFORE clause sanitizer ───────────────────

    /** Non-prose artifacts removed per config/output_cleanup.yml (see LlmOutputSanitizer). */
    private String stripLlmArtifacts(String raw) {
        return outputSanitizer.clean(raw);
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
    /** Last-resort placeholder cleanup — rules in drafting_defaults.yml {@code placeholder_rules}. */
    private String postProcessPlaceholders(String html, DraftRequest request) {
        Map<String, String> vars = Map.of(
                "PARTY_A", nullToDefault(request.getPartyA(), defaultPartyA(request)),
                "PARTY_B", nullToDefault(request.getPartyB(), defaultPartyB(request)),
                "JURISDICTION", nullToDefault(request.getJurisdiction(), draftingDefaults.prompt("jurisdiction_phrase")),
                "NOTICE_DAYS", nullToDefault(request.getNoticeDays(), draftingDefaults.form("NOTICE_DAYS")),
                "TERM_YEARS", nullToDefault(request.getTermYears(), draftingDefaults.form("TERM_YEARS")));
        return draftingDefaults.applyPlaceholderRules(html, vars);
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

        // Replace generic partyA variants with the correct role
        for (String variant : partyNameVariantsConfig.partyAVariants()) {
            if (!variant.equalsIgnoreCase(roleA) && !variant.equalsIgnoreCase("the " + roleA)) {
                html = html.replace(variant, roleA);
            }
        }
        // Replace generic partyB variants with the correct role
        for (String variant : partyNameVariantsConfig.partyBVariants()) {
            if (!variant.equalsIgnoreCase(roleB) && !variant.equalsIgnoreCase("the " + roleB)) {
                html = html.replace(variant, roleB);
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
    /** Distinct role words (leading "the " dropped) excluding the template's own roles. */
    private static List<String> driftSynonyms(List<String> variants, java.util.Set<String> ownRoles) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String v : variants) {
            String word = v.replaceFirst("(?i)^the\\s+", "").trim();
            if (!word.isEmpty() && !ownRoles.contains(word.toLowerCase())) out.add(word);
        }
        return new ArrayList<>(out);
    }

    private List<CoherenceIssue> runCoherenceScan(List<String> sections,
                                                   Map<String, String> sectionValues,
                                                   TerminologyManifest manifest,
                                                   String templateId) {
        List<CoherenceIssue> issues = new ArrayList<>();

        // Party-name drift: role words from party_name_variants.yml that are NOT this
        // template's own roles (contract_types.yml party_roles) — e.g. "Vendor" in a SaaS
        // draft whose roles are Provider/Customer.
        java.util.Set<String> ownRoles = new java.util.HashSet<>();
        contractRegistry.partyRoles(templateId).forEach(r -> ownRoles.add(r.toLowerCase()));
        List<String> vendorSynonyms = driftSynonyms(partyNameVariantsConfig.partyAVariants(), ownRoles);
        List<String> clientSynonyms = driftSynonyms(partyNameVariantsConfig.partyBVariants(), ownRoles);

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
        m.put("PARTY_A", nullToDefault(r.getPartyA(), defaultPartyA(r)));
        m.put("PARTY_B", nullToDefault(r.getPartyB(), defaultPartyB(r)));
        m.put("PARTY_A_ADDRESS", nullToDefault(r.getPartyAAddress(), draftingDefaults.form("PARTY_A_ADDRESS")));
        m.put("PARTY_B_ADDRESS", nullToDefault(r.getPartyBAddress(), draftingDefaults.form("PARTY_B_ADDRESS")));
        m.put("PARTY_A_REP", nullToDefault(r.getPartyARep(), draftingDefaults.form("PARTY_A_REP")));
        m.put("PARTY_B_REP", nullToDefault(r.getPartyBRep(), draftingDefaults.form("PARTY_B_REP")));
        m.put("EFFECTIVE_DATE", nullToDefault(r.getEffectiveDate(), java.time.LocalDate.now().toString()));
        m.put("JURISDICTION", nullToDefault(r.getJurisdiction(), defaultJurisdiction));
        m.put("AGREEMENT_REF", nullToDefault(r.getAgreementRef(), generateAgreementRef(r)));
        m.put("TERM_YEARS", nullToDefault(r.getTermYears(), draftingDefaults.form("TERM_YEARS")));
        m.put("NOTICE_DAYS", nullToDefault(r.getNoticeDays(), draftingDefaults.form("NOTICE_DAYS")));
        m.put("SURVIVAL_YEARS", nullToDefault(r.getSurvivalYears(), draftingDefaults.form("SURVIVAL_YEARS")));
        m.put("CONTRACT_TYPE_TITLE", resolveContractTypeName(r).toUpperCase());
        return m;
    }

    /** Auto-generate agreement reference: SLA-20260420-A1B2 */
    private String generateAgreementRef(DraftRequest r) {
        var typeConfig = contractRegistry.isKnown(r.getTemplateId()) ? contractRegistry.get(r.getTemplateId()) : null;
        String prefix = typeConfig != null && typeConfig.refPrefix() != null
                ? typeConfig.refPrefix() : draftingDefaults.agreementRefPrefix();
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

    /** Resolve default Party A name from contract_types.yml, falling back to "Party A". */
    private String defaultPartyA(DraftRequest request) {
        var config = contractRegistry.get(request.getTemplateId());
        return config != null ? config.partyARole() : "Party A";
    }

    /** Resolve default Party B name from contract_types.yml, falling back to "Party B". */
    private String defaultPartyB(DraftRequest request) {
        var config = contractRegistry.get(request.getTemplateId());
        return config != null ? config.partyBRole() : "Party B";
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
                        result = insertBeforeClosingTag(result, "\n" + GoldenClauseLibrary.toHtml(resolved).stripTrailing());
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
