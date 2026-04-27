package com.legalpartner.service.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.extraction.*;
import com.legalpartner.model.entity.AliasOverride;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.rag.DocumentFullTextRetriever;
import com.legalpartner.repository.AliasOverrideRepository;
import com.legalpartner.repository.DocumentMetadataRepository;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 10-step extraction pipeline:
 * [1] Multi-pass discovery → [2] Canonical mapping → [3] Dedup →
 * [4] Evidence validation → [5] Consistency → [6] Gap + negative →
 * [7] Confidence scoring → [8] Importance ranking → [9] Top risks summary →
 * [10] Store + corrections
 */
@Service
@Slf4j
public class ExtractionPipeline {

    private final DocumentFullTextRetriever fullTextRetriever;
    private final DocumentMetadataRepository documentRepository;
    private final DiscoveryPass discoveryPass;
    private final CanonicalMapper canonicalMapper;
    private final DedupStep dedupStep;
    private final EvidenceValidator evidenceValidator;
    private final ConsistencyChecker consistencyChecker;
    private final GapDetector gapDetector;
    private final ContractTypeDetector contractTypeDetector;
    private final ImportanceRanker importanceRanker;
    private final AliasOverrideRepository aliasOverrideRepo;
    private final ChatLanguageModel jsonChatModel;
    private final ObjectMapper objectMapper;

    public ExtractionPipeline(DocumentFullTextRetriever fullTextRetriever,
                               DocumentMetadataRepository documentRepository,
                               DiscoveryPass discoveryPass,
                               CanonicalMapper canonicalMapper,
                               DedupStep dedupStep,
                               EvidenceValidator evidenceValidator,
                               ConsistencyChecker consistencyChecker,
                               GapDetector gapDetector,
                               ContractTypeDetector contractTypeDetector,
                               ImportanceRanker importanceRanker,
                               AliasOverrideRepository aliasOverrideRepo,
                               @Qualifier("jsonChatModel") ChatLanguageModel jsonChatModel,
                               ObjectMapper objectMapper) {
        this.fullTextRetriever = fullTextRetriever;
        this.documentRepository = documentRepository;
        this.discoveryPass = discoveryPass;
        this.canonicalMapper = canonicalMapper;
        this.dedupStep = dedupStep;
        this.evidenceValidator = evidenceValidator;
        this.consistencyChecker = consistencyChecker;
        this.gapDetector = gapDetector;
        this.contractTypeDetector = contractTypeDetector;
        this.importanceRanker = importanceRanker;
        this.aliasOverrideRepo = aliasOverrideRepo;
        this.jsonChatModel = jsonChatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * Run the full extraction pipeline on a document.
     * Returns cached result if available and regenerate=false.
     */
    public ExtractionPipelineResult extract(UUID documentId, String username, boolean regenerate) {
        DocumentMetadata doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));

        // Check cache
        if (!regenerate && doc.getKeyTermsJson() != null && !doc.getKeyTermsJson().isBlank()) {
            try {
                ExtractionPipelineResult cached = objectMapper.readValue(doc.getKeyTermsJson(), ExtractionPipelineResult.class);
                log.info("Returning cached extraction for doc {} ({} entries)", documentId, cached.entries().size());
                return cached;
            } catch (Exception e) {
                log.warn("Failed to deserialize cached extraction for doc {}: {}", documentId, e.getMessage());
            }
        }

        // Get full document text
        String fullText = fullTextRetriever.retrieveFullTextUncapped(documentId);
        if (fullText == null || fullText.isBlank()) {
            throw new IllegalStateException("No text available for document " + documentId + ". Wait for indexing.");
        }

        log.info("Starting extraction pipeline for doc {} ({} chars)", documentId, fullText.length());

        // [6 prereq] Detect contract type (needed by discovery + gap check)
        ContractTypeDetection typeDetection = contractTypeDetector.detect(fullText);
        log.info("Contract type: {} (confidence={}, signals={})",
                typeDetection.contractType(), typeDetection.confidence(), typeDetection.signals());

        // [1] Multi-pass discovery
        List<ExtractionEntry> entries = discoveryPass.execute(fullText, typeDetection.contractType());

        // [2] Canonical mapping
        entries = entries.stream().map(e -> {
            CanonicalMapper.MappingResult mapping = canonicalMapper.map(e.rawField());
            String bucket = consistencyChecker.getBucket(mapping.canonicalField());
            return new ExtractionEntry(
                    mapping.canonicalField(), e.rawField(), e.value(), bucket,
                    e.evidence(), e.extractionConfidence(), e.consistencyStatus(),
                    mapping.confidence(), e.importance(), e.reasonCode(),
                    e.userCorrected(), e.sectionRef()
            );
        }).collect(Collectors.toList());

        // [3] Dedup
        entries = dedupStep.execute(entries);

        // [4] Evidence validation
        entries = evidenceValidator.validate(entries, fullText);

        // [5] Consistency checks
        ConsistencyChecker.ConsistencyResult consistencyResult = consistencyChecker.check(entries);
        entries = consistencyResult.entries();

        // [6] Gap + negative discovery
        entries = gapDetector.detect(entries, typeDetection, fullText);

        // [7] Confidence scoring — already set by evidence validator + consistency checker
        // Final pass: downgrade if evidence failed + consistency failed
        entries = entries.stream().map(e -> {
            if (ExtractionEntry.FAILED.equals(e.consistencyStatus())
                    && ExtractionEntry.LOW.equals(e.extractionConfidence())) {
                return e.withConfidence(ExtractionEntry.LOW);
            }
            return e;
        }).collect(Collectors.toList());

        // [8] Importance ranking
        entries = importanceRanker.rank(entries);

        // [9] Top risks summary
        List<ExtractionPipelineResult.RiskSummaryItem> topRisks = generateTopRisks(entries);

        // Compute stats
        int discovered = (int) entries.stream().filter(e -> e.reasonCode() == null).count();
        int validated = (int) entries.stream().filter(e -> ExtractionEntry.HIGH.equals(e.extractionConfidence())).count();
        int gaps = (int) entries.stream().filter(e -> e.reasonCode() != null).count();

        ExtractionPipelineResult result = new ExtractionPipelineResult(
                entries, typeDetection, consistencyResult.issues(), topRisks,
                discovered, validated, gaps, Instant.now()
        );

        // [10] Store — only cache if meaningful results
        if (discovered > 0) {
            try {
                doc.setKeyTermsJson(objectMapper.writeValueAsString(result));
                doc.setKeyTermsAt(Instant.now());
                documentRepository.save(doc);
                log.info("Extraction pipeline complete: {} discovered, {} validated, {} gaps, {} risks",
                        discovered, validated, gaps, topRisks.size());
            } catch (Exception e) {
                log.warn("Failed to cache extraction result: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * Apply a user correction to an extraction entry.
     * Also saves as alias override for self-improving mapping.
     */
    public ExtractionPipelineResult applyCorrection(UUID documentId, String canonicalField,
                                                     String correctedValue, String username, UUID userId) {
        DocumentMetadata doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found"));

        if (doc.getKeyTermsJson() == null) throw new IllegalStateException("No extraction to correct");

        try {
            ExtractionPipelineResult existing = objectMapper.readValue(doc.getKeyTermsJson(), ExtractionPipelineResult.class);
            List<ExtractionEntry> updated = existing.entries().stream()
                    .map(e -> {
                        if (Objects.equals(e.canonicalField(), canonicalField)
                                || Objects.equals(e.rawField(), canonicalField)) {
                            return e.withValue(correctedValue);
                        }
                        return e;
                    })
                    .collect(Collectors.toList());

            // Save alias override if the correction implies a mapping change
            ExtractionEntry corrected = updated.stream()
                    .filter(e -> e.userCorrected() && Objects.equals(e.canonicalField(), canonicalField))
                    .findFirst().orElse(null);
            if (corrected != null && corrected.rawField() != null && canonicalField != null
                    && !corrected.rawField().equals(canonicalField)) {
                aliasOverrideRepo.findByRawField(corrected.rawField().toLowerCase())
                        .ifPresentOrElse(
                                existing2 -> { existing2.setCanonicalField(canonicalField); aliasOverrideRepo.save(existing2); },
                                () -> aliasOverrideRepo.save(AliasOverride.builder()
                                        .rawField(corrected.rawField().toLowerCase())
                                        .canonicalField(canonicalField)
                                        .createdBy(userId)
                                        .build())
                        );
                log.info("Alias override saved: {} → {}", corrected.rawField(), canonicalField);
            }

            // Save correction log
            List<Map<String, String>> corrections = new ArrayList<>();
            if (doc.getKeyTermsCorrections() != null) {
                corrections = objectMapper.readValue(doc.getKeyTermsCorrections(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            }
            corrections.add(Map.of(
                    "field", canonicalField,
                    "correctedValue", correctedValue,
                    "correctedBy", username,
                    "correctedAt", Instant.now().toString()
            ));

            ExtractionPipelineResult updatedResult = new ExtractionPipelineResult(
                    updated, existing.contractTypeDetection(), existing.consistencyIssues(),
                    existing.topRisks(), existing.totalFieldsDiscovered(),
                    existing.totalFieldsValidated(), existing.totalGaps(), existing.generatedAt()
            );

            doc.setKeyTermsJson(objectMapper.writeValueAsString(updatedResult));
            doc.setKeyTermsCorrections(objectMapper.writeValueAsString(corrections));
            documentRepository.save(doc);

            return updatedResult;
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply correction: " + e.getMessage());
        }
    }

    /** Step 9: Generate top risks summary from HIGH importance + FAILED/WARNING entries */
    private List<ExtractionPipelineResult.RiskSummaryItem> generateTopRisks(List<ExtractionEntry> entries) {
        List<ExtractionEntry> riskEntries = entries.stream()
                .filter(e -> ExtractionEntry.HIGH.equals(e.importance())
                        || ExtractionEntry.FAILED.equals(e.consistencyStatus())
                        || ExtractionEntry.CONFLICTING_DUPLICATES.equals(e.consistencyStatus())
                        || e.reasonCode() != null)
                .limit(10)
                .toList();

        if (riskEntries.isEmpty()) return List.of();

        try {
            String input = riskEntries.stream()
                    .map(e -> {
                        String desc = e.canonicalField() != null ? e.canonicalField() : e.rawField();
                        if (e.reasonCode() != null) return desc + ": " + e.reasonCode();
                        if (ExtractionEntry.FAILED.equals(e.consistencyStatus())) return desc + ": consistency FAILED — " + e.value();
                        return desc + ": " + (e.value() != null ? e.value() : "no value");
                    })
                    .collect(Collectors.joining("\n"));

            String prompt = """
                    Based on these contract findings, identify the top 5 risks for legal review.
                    For each, provide a 1-line explanation and severity (HIGH/MEDIUM/LOW).

                    Findings:
                    """ + input + """

                    Output JSON only:
                    {"risks": [{"risk": "short description", "severity": "HIGH|MEDIUM|LOW", "explanation": "1-line why this matters"}]}
                    """;

            String response = jsonChatModel.generate(UserMessage.from(prompt)).content().text();
            String json = response.contains("{") ? response.substring(response.indexOf('{')) : response;
            var node = objectMapper.readTree(json);

            List<ExtractionPipelineResult.RiskSummaryItem> risks = new ArrayList<>();
            for (var item : node.path("risks")) {
                risks.add(new ExtractionPipelineResult.RiskSummaryItem(
                        item.path("risk").asText(),
                        item.path("severity").asText("MEDIUM"),
                        item.path("explanation").asText("")
                ));
            }
            return risks.stream().limit(5).toList();
        } catch (Exception e) {
            log.warn("Top risks summary generation failed: {}", e.getMessage());
            return List.of();
        }
    }
}
