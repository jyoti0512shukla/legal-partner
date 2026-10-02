package com.legalpartner.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskQuestionWaiverTest {

    private final RiskQuestionEngine engine = new RiskQuestionEngine();

    private static RiskQuestionEngine.RiskQuestion mutualCap() {
        return new RiskQuestionEngine.RiskQuestion("liability_cap_mutual", "Is the cap mutual?",
                "HIGH", "LOW", "Protection", List.of(), 9, true);
    }

    @Test
    void positionSensitiveQuestionIsWaivedOnlyForOneSidedDrafts() {
        assertThat(RiskQuestionEngine.isWaivedFor(mutualCap(), "PARTY_A")).isTrue();
        assertThat(RiskQuestionEngine.isWaivedFor(mutualCap(), "party_b")).isTrue();
        assertThat(RiskQuestionEngine.isWaivedFor(mutualCap(), "NEUTRAL")).isFalse();
        assertThat(RiskQuestionEngine.isWaivedFor(mutualCap(), null)).isFalse();
        var notSensitive = new RiskQuestionEngine.RiskQuestion("x", "q", "HIGH", "LOW", "c", List.of(), 9);
        assertThat(RiskQuestionEngine.isWaivedFor(notSensitive, "PARTY_A")).isFalse();
    }

    @Test
    void waivedAnswersDoNotRaiseClauseRiskOrScore() {
        var waived = new RiskQuestionEngine.QuestionResult(mutualCap(), true, RiskQuestionEngine.WAIVED, "note");
        var clause = engine.computeClauseRisk("LIABILITY", List.of(waived), true);
        assertThat(clause.overallRisk()).isEqualTo("LOW");

        var report = engine.computeFullReport(List.of(clause), List.of());
        // No scorable answers → neutral default, not a penalty from the waived question.
        assertThat(report.riskScore()).isEqualTo(50.0);
        assertThat(report.keyFindings()).isEmpty();
    }

    @Test
    void sameAnswerNotWaivedWouldBeHigh() {
        var no = new RiskQuestionEngine.QuestionResult(mutualCap(), true, "NO", "");
        assertThat(engine.computeClauseRisk("LIABILITY", List.of(no), true).overallRisk()).isEqualTo("HIGH");
    }

    @Test
    void yamlMarksMutualityQuestionsPositionSensitive() {
        engine.init();
        var liability = engine.getQuestionsForClause("LIABILITY", null);
        assertThat(liability).filteredOn(q -> q.id().equals("liability_cap_mutual"))
                .singleElement().extracting(RiskQuestionEngine.RiskQuestion::positionSensitive).isEqualTo(true);
        assertThat(liability).filteredOn(q -> q.id().equals("liability_cap_exists"))
                .singleElement().extracting(RiskQuestionEngine.RiskQuestion::positionSensitive).isEqualTo(false);
    }
}
