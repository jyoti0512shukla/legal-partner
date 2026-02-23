package com.legalpartner.rag;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class QueryExpander {

    private static final Map<String, List<String>> LEGAL_SYNONYMS = Map.ofEntries(
            Map.entry("termination", List.of("exit clause", "right to terminate", "notice of termination", "expiry")),
            Map.entry("liability", List.of("limitation of liability", "cap on liability", "damages", "liable")),
            Map.entry("indemnity", List.of("indemnification", "hold harmless", "indemnify")),
            Map.entry("governing law", List.of("jurisdiction", "applicable law", "choice of law")),
            Map.entry("confidential", List.of("non-disclosure", "NDA", "proprietary information")),
            Map.entry("force majeure", List.of("act of god", "unforeseen circumstances", "beyond control")),
            Map.entry("warranty", List.of("representation", "represents and warrants", "warranties")),
            Map.entry("intellectual property", List.of("IP rights", "patent", "copyright", "trademark")),
            Map.entry("payment", List.of("compensation", "fees", "invoice", "remuneration")),
            Map.entry("arbitration", List.of("dispute resolution", "mediation", "conciliation"))
    );

    public String expand(String query) {
        String lowerQuery = query.toLowerCase();
        StringBuilder expanded = new StringBuilder(query);

        for (var entry : LEGAL_SYNONYMS.entrySet()) {
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
