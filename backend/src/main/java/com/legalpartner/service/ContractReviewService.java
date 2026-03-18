package com.legalpartner.service;

import com.legalpartner.model.dto.ClauseCheckResult;
import com.legalpartner.model.dto.ContractReviewRequest;
import com.legalpartner.model.dto.ContractReviewResult;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.rag.DocumentFullTextRetriever;
import com.legalpartner.rag.PromptTemplates;
import com.legalpartner.repository.DocumentMetadataRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractReviewService {

    private final ChatLanguageModel chatModel;
    private final DocumentMetadataRepository documentRepository;
    private final DocumentFullTextRetriever fullTextRetriever;

    public ContractReviewResult review(ContractReviewRequest request, String username) {
        DocumentMetadata doc = documentRepository.findById(request.documentId())
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + request.documentId()));

        // Full document — checklist must see every clause to detect what's missing
        String context = fullTextRetriever.retrieveFullText(doc.getId());

        if (context.isBlank()) {
            return new ContractReviewResult(doc.getFileName(), "UNKNOWN", 0, 0, 0,
                    List.of(), List.of("Document not yet indexed"), List.of("Wait for INDEXED status"));
        }

        String prompt = String.format(PromptTemplates.CHECKLIST_USER, doc.getFileName(), context);

        String rawResponse = chatModel.generate(
                SystemMessage.from(PromptTemplates.CHECKLIST_SYSTEM),
                UserMessage.from(prompt)
        ).content().text();

        List<ClauseCheckResult> clauses = parseChecklistResponse(rawResponse);

        if (clauses.isEmpty()) {
            log.warn("Checklist review returned no structured clauses for doc {}", doc.getFileName());
            return new ContractReviewResult(doc.getFileName(), "UNKNOWN", 0, 0, 0,
                    List.of(), List.of(), List.of("Model returned unstructured response. Try again."));
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

    // Human-readable names for the 12 canonical CLAUSE_IDs
    private static final java.util.Map<String, String> CLAUSE_ID_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("LIABILITY_LIMIT",           "Limitation of Liability"),
            java.util.Map.entry("INDEMNITY",                 "Indemnification"),
            java.util.Map.entry("TERMINATION_CONVENIENCE",   "Termination for Convenience"),
            java.util.Map.entry("TERMINATION_CAUSE",         "Termination for Cause"),
            java.util.Map.entry("FORCE_MAJEURE",             "Force Majeure"),
            java.util.Map.entry("CONFIDENTIALITY",           "Confidentiality / NDA"),
            java.util.Map.entry("GOVERNING_LAW",             "Governing Law"),
            java.util.Map.entry("DISPUTE_RESOLUTION",        "Dispute Resolution / Arbitration"),
            java.util.Map.entry("IP_OWNERSHIP",              "Intellectual Property Ownership"),
            java.util.Map.entry("DATA_PROTECTION",           "Data Protection"),
            java.util.Map.entry("PAYMENT_TERMS",             "Payment Terms"),
            java.util.Map.entry("ASSIGNMENT",                "Assignment / Change of Control")
    );

    // Matches: "LIABILITY_LIMIT: PRESENT | HIGH | Section 8.1 | Finding. | Recommendation."
    // Also tolerates the model omitting some trailing fields.
    private static final java.util.regex.Pattern CHECKLIST_LINE =
            java.util.regex.Pattern.compile(
                    "(?:^|\\n)\\s*(\\w+):\\s*(PRESENT|WEAK|MISSING)" +
                    "(?:\\s*\\|\\s*(HIGH|MEDIUM|LOW))?" +
                    "(?:\\s*\\|\\s*([^|\\n]*))?" +   // section ref
                    "(?:\\s*\\|\\s*([^|\\n]*))?" +   // assessment
                    "(?:\\s*\\|\\s*([^|\\n]*))?",    // recommendation
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private List<ClauseCheckResult> parseChecklistResponse(String raw) {
        if (raw == null) return List.of();
        List<ClauseCheckResult> results = new ArrayList<>();

        java.util.regex.Matcher m = CHECKLIST_LINE.matcher(raw);
        while (m.find()) {
            String clauseId = m.group(1).trim().toUpperCase();
            if (!CLAUSE_ID_NAMES.containsKey(clauseId)) continue;

            String status     = m.group(2).trim().toUpperCase();
            String riskLevel  = m.group(3) != null ? m.group(3).trim().toUpperCase() : "MEDIUM";
            String sectionRef = m.group(4) != null ? m.group(4).trim() : "MISSING";
            String assessment = m.group(5) != null ? m.group(5).trim() : "";
            String recommendation = m.group(6) != null ? m.group(6).trim() : null;

            if (recommendation != null && (recommendation.isBlank() || "none".equalsIgnoreCase(recommendation))) {
                recommendation = null;
            }

            results.add(new ClauseCheckResult(
                    CLAUSE_ID_NAMES.getOrDefault(clauseId, clauseId),
                    status,
                    null,
                    sectionRef,
                    riskLevel,
                    assessment,
                    recommendation
            ));
        }

        if (results.isEmpty()) {
            log.warn("Checklist parser: no lines matched. Raw length={}, preview={}",
                    raw.length(), raw.substring(0, Math.min(300, raw.length())).replace('\n', ' '));
        }
        return results;
    }

    /** Return the value if it matches one of the allowed tokens (case-insensitive), else defaultVal. */
    private String normalise(String value, String defaultVal, String... allowed) {
        String v = value.trim().toUpperCase();
        for (String a : allowed) {
            if (a.equals(v)) return a;
        }
        return defaultVal;
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
