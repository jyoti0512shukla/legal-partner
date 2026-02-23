package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.*;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.rag.*;
import com.legalpartner.repository.DocumentMetadataRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
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
public class AiService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatLanguageModel chatModel;
    private final QueryExpander queryExpander;
    private final ReRanker reRanker;
    private final CitationExtractor citationExtractor;
    private final ResponseValidator responseValidator;
    private final EncryptionService encryptionService;
    private final DocumentMetadataRepository documentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${legalpartner.rag.candidate-count:15}")
    private int candidateCount;

    @Value("${legalpartner.rag.top-k:5}")
    private int topK;

    public QueryResult query(QueryRequest request, String username) {
        String expanded = queryExpander.expand(request.query());

        Embedding queryEmbedding = embeddingModel.embed(expanded).content();
        List<EmbeddingMatch<TextSegment>> candidates = embeddingStore.findRelevant(queryEmbedding, candidateCount);

        List<EmbeddingMatch<TextSegment>> ranked = reRanker.rerank(candidates, request.query(), topK);

        String context = assembleContext(ranked);
        String prompt = String.format(PromptTemplates.QUERY_USER, context, request.query());

        AiMessage response = chatModel.generate(
                SystemMessage.from(PromptTemplates.QUERY_SYSTEM),
                UserMessage.from(prompt)
        ).content();

        String rawAnswer = response.text();
        JsonNode parsed = responseValidator.parseAndValidate(rawAnswer);

        String answer;
        List<String> keyClauses;
        if (parsed != null && parsed.has("answer")) {
            answer = parsed.get("answer").asText();
            keyClauses = new ArrayList<>();
            if (parsed.has("key_clauses")) {
                parsed.get("key_clauses").forEach(n -> keyClauses.add(n.asText()));
            }
        } else {
            answer = rawAnswer;
            keyClauses = List.of();
        }

        List<Citation> citations = citationExtractor.extract(answer, ranked);
        long verified = citations.stream().filter(Citation::verified).count();
        String confidence = responseValidator.calibrateConfidence(ranked, verified, citations.size());
        List<String> warnings = responseValidator.checkFaithfulness(answer, ranked);

        return new QueryResult(answer, confidence, keyClauses, citations, warnings);
    }

    public CompareResult compare(CompareRequest request, String username) {
        DocumentMetadata doc1 = documentRepository.findById(request.documentId1())
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + request.documentId1()));
        DocumentMetadata doc2 = documentRepository.findById(request.documentId2())
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + request.documentId2()));
        String context1 = truncateForModel(retrieveContextForDocument(doc1.getId()), 1200);
        String context2 = truncateForModel(retrieveContextForDocument(doc2.getId()), 1200);

        if (context1.isBlank() || context2.isBlank()) {
            String which = "";
            if (context1.isBlank() && context2.isBlank()) which = "Neither document has indexed content.";
            else if (context1.isBlank()) which = doc1.getFileName() + " has no indexed content.";
            else which = doc2.getFileName() + " has no indexed content.";
            return new CompareResult(List.of(new ComparisonDimension(
                    "Documents not ready",
                    which,
                    "Go to Documents → delete any FAILED entries, re-upload, and wait for INDEXED status.",
                    "neutral",
                    "Processing may still be running, or upload may have failed earlier."
            )));
        }

        String prompt = String.format(PromptTemplates.COMPARE_USER,
                doc1.getFileName(), context1, doc2.getFileName(), context2);

        AiMessage response = chatModel.generate(
                SystemMessage.from(PromptTemplates.COMPARE_SYSTEM),
                UserMessage.from(prompt)
        ).content();

        JsonNode parsed = responseValidator.parseAndValidate(response.text());
        List<ComparisonDimension> dimensions = new ArrayList<>();

        if (parsed != null && parsed.has("dimensions")) {
            for (JsonNode dim : parsed.get("dimensions")) {
                String favorable = dim.path("favorable_to").asText("neutral");
                if (!favorable.matches("doc1|doc2|neutral")) favorable = "neutral";
                dimensions.add(new ComparisonDimension(
                        dim.path("name").asText(),
                        dim.path("doc1_summary").asText(),
                        dim.path("doc2_summary").asText(),
                        favorable,
                        dim.path("reasoning").asText()
                ));
            }
        }

        if (dimensions.isEmpty()) {
            log.warn("Compare produced no dimensions; LLM may have returned invalid JSON");
            dimensions.add(new ComparisonDimension(
                    "Analysis",
                    "Insufficient structured output from model.",
                    "Try comparing contracts (not legislation) or use documents with clearer clause structure.",
                    "neutral",
                    "TinyLlama sometimes returns prose instead of JSON. Retrying may help."
            ));
        }

        return new CompareResult(dimensions);
    }

    private String truncateForModel(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[...truncated]";
    }

    public RiskAssessmentResult assessRisk(UUID documentId, String username) {
        DocumentMetadata doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));

        String riskQuery = "liability limitation indemnity warranties termination governed law force majeure";
        Embedding queryEmbedding = embeddingModel.embed(riskQuery).content();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, 10);

        String context = assembleContext(matches);
        String prompt = String.format(PromptTemplates.RISK_USER, doc.getFileName(), context);

        AiMessage response = chatModel.generate(
                SystemMessage.from(PromptTemplates.RISK_SYSTEM),
                UserMessage.from(prompt)
        ).content();

        JsonNode parsed = responseValidator.parseAndValidate(response.text());
        String overallRisk = "MEDIUM";
        List<RiskCategory> categories = new ArrayList<>();

        if (parsed != null) {
            overallRisk = parsed.path("overall_risk").asText("MEDIUM");
            if (parsed.has("categories")) {
                for (JsonNode cat : parsed.get("categories")) {
                    categories.add(new RiskCategory(
                            cat.path("name").asText(),
                            cat.path("rating").asText(),
                            cat.path("justification").asText(),
                            cat.path("clause_reference").asText()
                    ));
                }
            }
        }

        return new RiskAssessmentResult(overallRisk, categories);
    }

    private String assembleContext(List<EmbeddingMatch<TextSegment>> matches) {
        return matches.stream()
                .sorted(Comparator.comparing(m -> {
                    String idx = m.embedded().metadata().getString("chunk_index");
                    return idx != null ? Integer.parseInt(idx) : 0;
                }))
                .map(m -> {
                    String decrypted;
                    try {
                        decrypted = encryptionService.decrypt(m.embedded().text());
                    } catch (Exception e) {
                        decrypted = m.embedded().text();
                    }
                    String fileName = m.embedded().metadata().getString("file_name");
                    String section = m.embedded().metadata().getString("section_path");
                    return String.format("[Source: %s | %s]\n%s",
                            fileName != null ? fileName : "Unknown",
                            section != null ? section : "",
                            decrypted);
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String retrieveContextForDocument(UUID docId) {
        // Use broad queries so both contracts and legislation (e.g. Indian Contract Act) match
        List<String> queries = List.of(
                "liability termination indemnity confidentiality governing law",
                "contract agreement obligations breach consideration sections"
        );
        Set<String> seen = new java.util.HashSet<>();
        List<EmbeddingMatch<TextSegment>> forDoc = new ArrayList<>();

        for (String searchQuery : queries) {
            Embedding embedding = embeddingModel.embed(searchQuery).content();
            List<EmbeddingMatch<TextSegment>> all = embeddingStore.findRelevant(embedding, 50);
            for (EmbeddingMatch<TextSegment> m : all) {
                if (!docId.toString().equals(m.embedded().metadata().getString("document_id"))) continue;
                String key = m.embedded().metadata().getString("chunk_index") + ":" + m.embedded().text().hashCode();
                if (seen.add(key)) {
                    forDoc.add(m);
                    if (forDoc.size() >= 5) break;
                }
            }
            if (forDoc.size() >= 5) break;
        }

        return assembleContext(forDoc.stream().limit(5).toList());
    }
}
