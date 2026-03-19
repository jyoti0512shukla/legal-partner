package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalpartner.model.dto.ClauseCheckResult;
import com.legalpartner.model.dto.ContractReviewRequest;
import com.legalpartner.model.dto.ContractReviewResult;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.rag.DocumentFullTextRetriever;
import com.legalpartner.rag.PromptTemplates;
import com.legalpartner.rag.StructuredSchemas;
import com.legalpartner.rag.VllmGuidedClient;
import com.legalpartner.repository.DocumentMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractReviewService {

    private final DocumentMetadataRepository documentRepository;
    private final DocumentFullTextRetriever fullTextRetriever;
    private final VllmGuidedClient guidedClient;

    // Human-readable names for the 12 canonical clause IDs
    private static final Map<String, String> CLAUSE_ID_NAMES = Map.ofEntries(
            Map.entry("LIABILITY_LIMIT",           "Limitation of Liability"),
            Map.entry("INDEMNITY",                 "Indemnification"),
            Map.entry("TERMINATION_CONVENIENCE",   "Termination for Convenience"),
            Map.entry("TERMINATION_CAUSE",         "Termination for Cause"),
            Map.entry("FORCE_MAJEURE",             "Force Majeure"),
            Map.entry("CONFIDENTIALITY",           "Confidentiality / NDA"),
            Map.entry("GOVERNING_LAW",             "Governing Law"),
            Map.entry("DISPUTE_RESOLUTION",        "Dispute Resolution / Arbitration"),
            Map.entry("IP_OWNERSHIP",              "Intellectual Property Ownership"),
            Map.entry("DATA_PROTECTION",           "Data Protection"),
            Map.entry("PAYMENT_TERMS",             "Payment Terms"),
            Map.entry("ASSIGNMENT",                "Assignment / Change of Control")
    );

    public ContractReviewResult review(ContractReviewRequest request, String username) {
        DocumentMetadata doc = documentRepository.findById(request.documentId())
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + request.documentId()));

        String context = fullTextRetriever.retrieveFullText(doc.getId());

        if (context.isBlank()) {
            return new ContractReviewResult(doc.getFileName(), "UNKNOWN", 0, 0, 0,
                    List.of(), List.of("Document not yet indexed"), List.of("Wait for INDEXED status"));
        }

        String prompt = String.format(PromptTemplates.CHECKLIST_USER, doc.getFileName(), context);

        JsonNode json = guidedClient.generateStructured(
                PromptTemplates.CHECKLIST_SYSTEM_GUIDED, prompt,
                StructuredSchemas.CHECKLIST_SCHEMA, 1500);

        List<ClauseCheckResult> clauses = new ArrayList<>();
        for (JsonNode c : json.path("clauses")) {
            String clauseId = c.path("clause_id").asText("");
            String recommendation = c.has("recommendation") && !c.path("recommendation").isNull()
                    ? c.path("recommendation").asText(null) : null;
            if (recommendation != null && (recommendation.isBlank() || "none".equalsIgnoreCase(recommendation))) {
                recommendation = null;
            }
            clauses.add(new ClauseCheckResult(
                    CLAUSE_ID_NAMES.getOrDefault(clauseId, clauseId),
                    c.path("status").asText("MISSING"),
                    null,
                    c.path("section_ref").asText("MISSING"),
                    c.path("risk_level").asText("MEDIUM"),
                    c.path("finding").asText(""),
                    recommendation
            ));
        }

        if (clauses.isEmpty()) {
            log.warn("Checklist review returned no clauses for doc {}", doc.getFileName());
            return new ContractReviewResult(doc.getFileName(), "UNKNOWN", 0, 0, 0,
                    List.of(), List.of(), List.of("Model returned empty response. Try again."));
        }

        long present = clauses.stream().filter(c -> "PRESENT".equals(c.status())).count();
        long missing = clauses.stream().filter(c -> "MISSING".equals(c.status())).count();
        long weak = clauses.stream().filter(c -> "WEAK".equals(c.status())).count();

        List<String> criticalMissing = clauses.stream()
                .filter(c -> "MISSING".equals(c.status()) && "HIGH".equals(c.riskLevel()))
                .map(ClauseCheckResult::clauseName)
                .toList();

        List<String> recommendations = clauses.stream()
                .filter(c -> c.recommendation() != null && !c.recommendation().isBlank())
                .filter(c -> !"PRESENT".equals(c.status()) || "HIGH".equals(c.riskLevel()))
                .map(c -> c.clauseName() + ": " + c.recommendation())
                .limit(5)
                .toList();

        String overallRisk = computeOverallRisk(clauses);

        return new ContractReviewResult(
                doc.getFileName(), overallRisk,
                (int) present, (int) missing, (int) weak,
                clauses, criticalMissing, recommendations
        );
    }

    private String computeOverallRisk(List<ClauseCheckResult> clauses) {
        long highCount = clauses.stream().filter(c -> "HIGH".equals(c.riskLevel())).count();
        long mediumCount = clauses.stream().filter(c -> "MEDIUM".equals(c.riskLevel())).count();
        if (highCount >= 3 || (highCount >= 1 && clauses.stream()
                .anyMatch(c -> "MISSING".equals(c.status()) && "HIGH".equals(c.riskLevel())))) return "HIGH";
        if (highCount >= 1 || mediumCount >= 4) return "MEDIUM";
        return "LOW";
    }

}
