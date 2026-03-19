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
public class RedlineSuggestionsResult {

    private List<RedlineSuggestion> suggestions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RedlineSuggestion {
        private String clauseName;
        private String issue;
        private String suggestedLanguage;
        private String rationale;
    }
}
