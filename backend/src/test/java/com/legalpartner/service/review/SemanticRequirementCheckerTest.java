package com.legalpartner.service.review;

import com.legalpartner.service.RiskQuestionEngine;
import com.legalpartner.service.RiskQuestionEngine.RiskQuestion;
import com.legalpartner.service.TokenBudgetService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticRequirementCheckerTest {

    private static TokenBudgetService budget() {
        TokenBudgetService t = new TokenBudgetService();
        ReflectionTestUtils.setField(t, "modelWindowTokens", 8192);
        return t;
    }

    private static RiskQuestion q(String id, String text, boolean positionSensitive) {
        return new RiskQuestion(id, text, "HIGH", "LOW", "c", List.of(), 9, positionSensitive);
    }

    @Test
    void parsesJsonAnswers() {
        var r = SemanticRequirementChecker.parseAnswer(q("a", "q", false),
                "Sure: {\"answer\":\"PRESENT\",\"quote\":\"shall not exceed\"}");
        assertThat(r.answer()).isEqualTo("YES");
        assertThat(r.quotedEvidence()).isEqualTo("shall not exceed");
        assertThat(SemanticRequirementChecker.parseAnswer(q("a", "q", false), "{\"answer\":\"UNCLEAR\"}").answer())
                .isEqualTo("NO");
        assertThat(SemanticRequirementChecker.parseAnswer(q("a", "q", false), "PRESENT, see 2.1").answer())
                .isEqualTo("YES");
    }

    @Test
    void waivesPositionSensitiveQuestionsWithoutCallingTheModel() {
        AtomicInteger calls = new AtomicInteger();
        ChatLanguageModel model = messages -> {
            calls.incrementAndGet();
            String prompt = ((UserMessage) messages.get(0)).singleText();
            return Response.from(AiMessage.from(prompt.contains("Question:")
                    ? "{\"answer\":\"PRESENT\",\"quote\":\"cap\"}" : "1. cap provision"));
        };
        var checker = new SemanticRequirementChecker(model, budget(), com.legalpartner.testsupport.ConfigFixtures.prompts());
        List<RiskQuestionEngine.QuestionResult> streamed = new ArrayList<>();

        var results = checker.evaluate("LIABILITY", "Liability is capped at fees paid.",
                List.of(q("cap_exists", "Is there a cap?", false), q("cap_mutual", "Is it mutual?", true)),
                "PARTY_A", streamed::add);

        assertThat(results).extracting(RiskQuestionEngine.QuestionResult::answer)
                .containsExactlyInAnyOrder("YES", RiskQuestionEngine.WAIVED);
        assertThat(streamed).hasSize(2);
        // 1 provision extraction + 1 question; the waived question costs nothing.
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void modelFailureYieldsUnansweredNotCrash() {
        ChatLanguageModel failing = messages -> { throw new RuntimeException("down"); };
        var results = new SemanticRequirementChecker(failing, budget(), com.legalpartner.testsupport.ConfigFixtures.prompts())
                .evaluate("LIABILITY", "text", List.of(q("x", "q", false)), null, null);
        assertThat(results).singleElement().extracting(RiskQuestionEngine.QuestionResult::answered).isEqualTo(false);
    }
}
