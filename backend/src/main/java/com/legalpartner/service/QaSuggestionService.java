package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.rag.DocumentFullTextRetriever;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * Per-contract-type Q&A suggestions.
 * - Typed documents → loads from qa_suggestions.yml (instant, no LLM)
 * - Untyped documents → LLM reads the contract and generates relevant questions
 */
@Service
@Slf4j
public class QaSuggestionService {

    private final ChatLanguageModel shortChatModel;
    private final DocumentFullTextRetriever fullTextRetriever;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Map<String, List<String>> suggestions = new HashMap<>();

    /** Cache LLM-generated suggestions per document to avoid re-generating */
    private final Map<UUID, List<String>> generatedCache = new java.util.concurrent.ConcurrentHashMap<>();

    public QaSuggestionService(@Qualifier("shortChatModel") ChatLanguageModel shortChatModel,
                                DocumentFullTextRetriever fullTextRetriever) {
        this.shortChatModel = shortChatModel;
        this.fullTextRetriever = fullTextRetriever;
    }

    @PostConstruct
    void init() {
        try {
            InputStream is = new ClassPathResource("config/qa_suggestions.yml").getInputStream();
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            @SuppressWarnings("unchecked")
            Map<String, List<String>> loaded = (Map<String, List<String>>) root.get("suggestions");
            if (loaded != null) {
                suggestions = loaded;
            }
            log.info("QaSuggestionService loaded suggestions for {} contract types", suggestions.size());
        } catch (Exception e) {
            log.error("Failed to load qa_suggestions.yml: {}", e.getMessage());
        }
    }

    /**
     * Get suggestions for a typed document — instant from YAML.
     */
    public List<String> getSuggestions(String contractType) {
        List<String> result = suggestions.get(contractType);
        if (result != null && !result.isEmpty()) return result;
        return suggestions.getOrDefault("_default", getDefaultSuggestions());
    }

    /**
     * Get suggestions for a document — uses type if available, otherwise asks LLM.
     */
    public List<String> getSuggestionsForDocument(UUID documentId, String contractType) {
        // If typed, use config
        if (contractType != null && !contractType.isBlank() && !"_default".equals(contractType) && !"OTHER".equals(contractType)) {
            List<String> typed = suggestions.get(contractType);
            if (typed != null && !typed.isEmpty()) return typed;
        }

        // Check cache for previously generated suggestions
        List<String> cached = generatedCache.get(documentId);
        if (cached != null) return cached;

        // No type — ask LLM to generate questions based on the document content
        try {
            String text = fullTextRetriever.retrieveFullText(documentId);
            if (text == null || text.isBlank()) return getDefaultSuggestions();

            String capped = text.length() > 3000 ? text.substring(0, 3000) : text;
            String prompt = """
                    Read this contract excerpt and suggest the 8 most important questions a lawyer should ask during review.
                    Focus on: risks, missing protections, unusual terms, financial implications, and termination rights.
                    Make questions specific to THIS contract, not generic.

                    Contract:
                    %s

                    Output ONLY a JSON array of strings:
                    ["question 1", "question 2", ...]
                    """.formatted(capped);

            String response = shortChatModel.generate(UserMessage.from(prompt)).content().text().trim();

            // Parse JSON array
            String json = response;
            if (json.contains("[")) json = json.substring(json.indexOf('['));
            if (json.contains("]")) json = json.substring(0, json.lastIndexOf(']') + 1);

            JsonNode arr = objectMapper.readTree(json);
            List<String> generated = new ArrayList<>();
            for (JsonNode node : arr) {
                String q = node.asText("").trim();
                if (!q.isBlank() && q.length() > 10) generated.add(q);
            }

            if (generated.size() >= 3) {
                generatedCache.put(documentId, generated);
                log.info("Generated {} Q&A suggestions for untyped document {}", generated.size(), documentId);
                return generated;
            }
        } catch (Exception e) {
            log.warn("Failed to generate Q&A suggestions for doc {}: {}", documentId, e.getMessage());
        }

        return getDefaultSuggestions();
    }

    private List<String> getDefaultSuggestions() {
        return suggestions.getOrDefault("_default", List.of(
                "Who are the parties to this agreement?",
                "What is the termination notice period?",
                "Is liability capped? If so, to what amount?",
                "What is the governing law?"
        ));
    }

    public Set<String> getAvailableTypes() {
        return suggestions.keySet();
    }
}
