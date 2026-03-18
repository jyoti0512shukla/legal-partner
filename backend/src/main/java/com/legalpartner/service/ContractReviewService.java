package com.legalpartner.service;

import com.legalpartner.model.dto.ClauseCheckResult;
import com.legalpartner.model.dto.ContractReviewRequest;
import com.legalpartner.model.dto.ContractReviewResult;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.rag.PromptTemplates;
import com.legalpartner.repository.DocumentMetadataRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractReviewService {

    private final ChatLanguageModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentMetadataRepository documentRepository;
    private final EncryptionService encryptionService;
    @Value("${legalpartner.rag.retrieve-candidates:200}")
    private int retrieveCandidates;

    public ContractReviewResult review(ContractReviewRequest request, String username) {
        DocumentMetadata doc = documentRepository.findById(request.documentId())
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + request.documentId()));

        String context = retrieveComprehensiveContext(doc.getId());

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

    private List<ClauseCheckResult> parseChecklistResponse(String raw) {
        if (raw == null) return List.of();
        List<ClauseCheckResult> results = new ArrayList<>();

        for (String line : raw.split("\\r?\\n")) {
            line = line.trim();
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length < 5) continue; // must have at least CLAUSE_ID|STATUS|RISK|REF|ASSESSMENT

            String clauseId   = parts[0].trim().toUpperCase();
            // Validate it's one of our known IDs (skip echo/header lines)
            if (!CLAUSE_ID_NAMES.containsKey(clauseId)) continue;

            String status     = normalise(parts[1], "PRESENT", "PRESENT", "MISSING", "WEAK");
            String riskLevel  = normalise(parts[2], "MEDIUM",  "HIGH", "MEDIUM", "LOW");
            String sectionRef = parts[3].trim();
            String assessment = parts.length > 4 ? parts[4].trim() : "";
            String recommendation = parts.length > 5 ? parts[5].trim() : null;

            if ("none".equalsIgnoreCase(recommendation) || "".equals(recommendation)) recommendation = null;

            results.add(new ClauseCheckResult(
                    CLAUSE_ID_NAMES.getOrDefault(clauseId, clauseId),
                    status,
                    null, // foundText — not extracted in pipe format
                    sectionRef,
                    riskLevel,
                    assessment,
                    recommendation
            ));
        }

        // Log raw response if we got nothing parseable
        if (results.isEmpty()) {
            log.warn("Checklist pipe-parser: no lines matched. Raw length={}, preview={}",
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

    private String retrieveComprehensiveContext(UUID docId) {
        List<String> queries = List.of(
                "liability indemnity limitation damages exclusion consequential",
                "termination notice period cause breach force majeure",
                "confidentiality NDA governing law arbitration dispute resolution",
                "intellectual property ownership work product assignment data protection",
                "payment terms invoice assignment change of control"
        );
        Set<String> seen = new HashSet<>();
        List<EmbeddingMatch<TextSegment>> forDoc = new ArrayList<>();

        for (String q : queries) {
            if (forDoc.size() >= 15) break;
            Embedding emb = embeddingModel.embed(q).content();
            List<EmbeddingMatch<TextSegment>> all = embeddingStore.findRelevant(emb, retrieveCandidates);
            for (EmbeddingMatch<TextSegment> m : all) {
                if (!docId.toString().equals(m.embedded().metadata().getString("document_id"))) continue;
                String key = m.embedded().metadata().getString("chunk_index") + ":" + m.embedded().text().hashCode();
                if (seen.add(key)) {
                    forDoc.add(m);
                    if (forDoc.size() >= 15) break;
                }
            }
        }

        return forDoc.stream()
                .sorted(Comparator.comparing(m -> {
                    String idx = m.embedded().metadata().getString("chunk_index");
                    return idx != null ? Integer.parseInt(idx) : 0;
                }))
                .map(m -> {
                    String text;
                    try { text = encryptionService.decrypt(m.embedded().text()); }
                    catch (Exception e) { text = m.embedded().text(); }
                    String section = m.embedded().metadata().getString("section_path");
                    return String.format("[%s]\n%s", section != null ? section : "", text);
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
