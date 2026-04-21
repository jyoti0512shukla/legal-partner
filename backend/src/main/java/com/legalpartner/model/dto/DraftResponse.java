package com.legalpartner.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftResponse {

    private String draftHtml;
    /** Draft generation parameters HTML — displayed separately from the contract body. */
    private String draftParametersHtml;
    private List<ClauseSuggestion> suggestions;
    /** clauseKey → list of QA warning strings. Populated when post-generation validation finds issues. */
    private Map<String, List<String>> qaWarnings;
    /** Cross-clause coherence issues found by the post-generation coherence scan. */
    private List<String> coherenceIssues;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClauseSuggestion {
        private String clauseRef;
        private String currentText;
        private String suggestion;
        private String reasoning;
    }
}
