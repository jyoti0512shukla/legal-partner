package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private List<ClauseCheckResult> parseChecklistResponse(String raw) {
        if (raw == null) return List.of();
        String cleaned = raw.trim();
        List<ClauseCheckResult> results = new ArrayList<>();

        JsonNode root = null;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                try { root = objectMapper.readTree(cleaned.substring(start, end + 1)); }
                catch (Exception ignored) {}
            }
        }
        if (root == null || !root.isArray()) return List.of();

        for (JsonNode item : root) {
            try {
                results.add(new ClauseCheckResult(
                        item.path("clause_name").asText("Unknown"),
                        item.path("status").asText("PRESENT"),
                        nullableText(item, "found_text"),
                        item.path("section_ref").asText(""),
                        item.path("risk_level").asText("MEDIUM"),
                        item.path("assessment").asText(""),
                        nullableText(item, "recommendation")
                ));
            } catch (Exception e) {
                log.debug("Failed to parse checklist item: {}", item);
            }
        }
        return results;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
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
