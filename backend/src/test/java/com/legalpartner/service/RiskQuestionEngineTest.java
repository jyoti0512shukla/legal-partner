package com.legalpartner.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskQuestionEngineTest {

    private final RiskQuestionEngine engine = new RiskQuestionEngine();

    private static RiskQuestionEngine.RiskQuestion question(String id, String riskIfNo, int weight) {
        return new RiskQuestionEngine.RiskQuestion(
                id, "Question " + id + "?", riskIfNo, "LOW", "Test", List.of(), weight);
    }

    private static RiskQuestionEngine.QuestionResult answered(
            RiskQuestionEngine.RiskQuestion q, String answer) {
        return new RiskQuestionEngine.QuestionResult(q, true, answer, "evidence");
    }

    // ── computeClauseRisk: weight-aware level ────────────────────────────────

    @Test
    void singleLowWeightHighRiskAnswerYieldsMedium() {
        var results = List.of(
                answered(question("q1", "HIGH", 7), "NO"),
                answered(question("q2", "HIGH", 8), "YES"));
        var risk = engine.computeClauseRisk("LIABILITY", results, true);
        assertThat(risk.overallRisk()).isEqualTo("MEDIUM");
    }

    @Test
    void heavyweightHighRiskAnswerYieldsHigh() {
        var results = List.of(answered(question("cap_exists", "HIGH", 10), "NO"));
        var risk = engine.computeClauseRisk("LIABILITY", results, true);
        assertThat(risk.overallRisk()).isEqualTo("HIGH");
    }

    @Test
    void twoHighRiskAnswersYieldHigh() {
        var results = List.of(
                answered(question("q1", "HIGH", 5), "NO"),
                answered(question("q2", "HIGH", 6), "NO"));
        var risk = engine.computeClauseRisk("LIABILITY", results, true);
        assertThat(risk.overallRisk()).isEqualTo("HIGH");
    }

    @Test
    void mediumRiskAnswersYieldMedium() {
        var results = List.of(
                answered(question("q1", "MEDIUM", 8), "NO"),
                answered(question("q2", "HIGH", 9), "YES"));
        var risk = engine.computeClauseRisk("LIABILITY", results, true);
        assertThat(risk.overallRisk()).isEqualTo("MEDIUM");
    }

    @Test
    void allProtectionsPresentYieldsLow() {
        var results = List.of(
                answered(question("q1", "HIGH", 10), "YES"),
                answered(question("q2", "MEDIUM", 7), "YES"));
        var risk = engine.computeClauseRisk("LIABILITY", results, true);
        assertThat(risk.overallRisk()).isEqualTo("LOW");
    }

    @Test
    void missingClauseIsHigh() {
        var risk = engine.computeClauseRisk("INDEMNIFICATION", List.of(), false);
        assertThat(risk.overallRisk()).isEqualTo("HIGH");
        assertThat(risk.clausePresent()).isFalse();
    }

    // ── computeFullReport: aggregation ───────────────────────────────────────

    @Test
    void singleMissingClauseDoesNotSwampWellScoredContract() {
        // 3 present clauses, all protections in place
        var goodResults = List.of(
                answered(question("q1", "HIGH", 10), "YES"),
                answered(question("q2", "HIGH", 9), "YES"),
                answered(question("q3", "MEDIUM", 7), "YES"));
        var present1 = engine.computeClauseRisk("LIABILITY", goodResults, true);
        var present2 = engine.computeClauseRisk("TERMINATION", goodResults, true);
        var present3 = engine.computeClauseRisk("CONFIDENTIALITY", goodResults, true);
        var missing = engine.computeClauseRisk("INDEMNIFICATION", List.of(), false);

        var report = engine.computeFullReport(
                List.of(present1, present2, present3, missing), List.of("INDEMNIFICATION"));

        // One gap flags MEDIUM overall (any HIGH clause escalates LOW→MEDIUM),
        // but must not flag the whole contract HIGH
        assertThat(report.overallRisk()).isEqualTo("MEDIUM");
        assertThat(report.missingClauses()).containsExactly("INDEMNIFICATION");
        assertThat(report.riskScore()).isLessThan(60.0);
    }

    @Test
    void threeHighClausesEscalateToHighOverall() {
        var badResults = List.of(answered(question("q1", "HIGH", 10), "NO"));
        var high1 = engine.computeClauseRisk("LIABILITY", badResults, true);
        var high2 = engine.computeClauseRisk("TERMINATION", badResults, true);
        var high3 = engine.computeClauseRisk("INDEMNIFICATION", badResults, true);

        var report = engine.computeFullReport(List.of(high1, high2, high3), List.of());
        assertThat(report.overallRisk()).isEqualTo("HIGH");
    }

    @Test
    void cleanContractIsLow() {
        var goodResults = List.of(
                answered(question("q1", "HIGH", 10), "YES"),
                answered(question("q2", "MEDIUM", 7), "YES"));
        var present = engine.computeClauseRisk("LIABILITY", goodResults, true);

        var report = engine.computeFullReport(List.of(present), List.of());
        assertThat(report.overallRisk()).isEqualTo("LOW");
        assertThat(report.riskScore()).isEqualTo(0.0);
    }
}
