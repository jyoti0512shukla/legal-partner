package com.legalpartner.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftResponse {

    private String draftHtml;
    private List<ClauseSuggestion> suggestions;

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
