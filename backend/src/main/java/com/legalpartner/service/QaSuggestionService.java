package com.legalpartner.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * Loads per-contract-type Q&A suggestions from qa_suggestions.yml.
 * Returns the right question set based on detected contract type.
 */
@Service
@Slf4j
public class QaSuggestionService {

    private Map<String, List<String>> suggestions = new HashMap<>();

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

    public List<String> getSuggestions(String contractType) {
        // Try exact match first, then default
        List<String> result = suggestions.get(contractType);
        if (result != null && !result.isEmpty()) return result;
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
