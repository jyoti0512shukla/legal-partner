package com.legalpartner.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RiskAssessmentResult(
        String overallRisk,
        List<RiskCategory> categories,
        // ── New structured fields (null when legacy path is used) ───────
        Double riskScore,
        List<ClauseRiskDetail> clauseResults,
        List<String> missingClauses,
        List<String> keyFindings
) {
    /** Legacy constructor — keeps backward compatibility with existing code paths. */
    public RiskAssessmentResult(String overallRisk, List<RiskCategory> categories) {
        this(overallRisk, categories, null, null, null, null);
    }

    /** Per-clause structured breakdown (new risk engine). */
    public record ClauseRiskDetail(
            String clauseType,
            String overallRisk,
            boolean clausePresent,
            List<QuestionAnswer> questions
    ) {}

    /** Individual YES/NO question answer with evidence quote. */
    public record QuestionAnswer(
            String id,
            String question,
            String category,
            String answer,
            String quotedEvidence,
            String riskIfNo,
            String riskIfYes,
            int weight
    ) {}
}
