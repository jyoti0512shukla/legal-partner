package com.legalpartner.rag;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class QueryExpander {

    /** Synonyms and acronyms from config/vocabulary.yml (query_expansion). */
    private final com.legalpartner.config.LegalVocabulary vocabulary;

    public QueryExpander(com.legalpartner.config.LegalVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }



    public String expand(String query) {
        String lowerQuery = query.toLowerCase();
        StringBuilder expanded = new StringBuilder(query);

        // Acronym expansion (whole-word)
        String withAcronyms = " " + lowerQuery + " ";
        for (Map.Entry<String, String> entry : vocabulary.acronymExpansions().entrySet()) {
            if (withAcronyms.contains(" " + entry.getKey() + " ")) {
                expanded.append(" ").append(entry.getValue().trim());
            }
        }

        // Stem-based synonym expansion
        for (Map.Entry<String, List<String>> entry : vocabulary.stemSynonyms().entrySet()) {
            if (lowerQuery.contains(entry.getKey())) {
                for (String synonym : entry.getValue()) {
                    if (!lowerQuery.contains(synonym.toLowerCase())) {
                        expanded.append(" ").append(synonym);
                    }
                }
            }
        }
        return expanded.toString();
    }
}
