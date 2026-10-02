package com.legalpartner.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Review calibration multipliers change how much a question moves the risk score. */
class RiskScoreCalibrationTest {

    private final RiskQuestionEngine engine = com.legalpartner.testsupport.ConfigFixtures.riskQuestions();

    private static RiskQuestionEngine.QuestionResult answer(String id, String ans) {
        var q = new RiskQuestionEngine.RiskQuestion(id, id + "?", "HIGH", "LOW", "c", List.of(), 9, false);
        return new RiskQuestionEngine.QuestionResult(q, true, ans, "");
    }

    @Test
    void distrustedQuestionMovesTheScoreLess() {
        var clause = new RiskQuestionEngine.ClauseRiskResult("LIABILITY", "MEDIUM",
                List.of(answer("often_wrong", "NO"), answer("reliable", "YES")), true);
        double plain = engine.computeFullReport(List.of(clause), List.of()).riskScore();
        double calibrated = engine.computeFullReport(List.of(clause), List.of(), Map.of("often_wrong", 0.3)).riskScore();
        assertThat(calibrated).isLessThan(plain);
        assertThat(engine.computeFullReport(List.of(clause), List.of(), Map.of()).riskScore()).isEqualTo(plain);
    }
}
