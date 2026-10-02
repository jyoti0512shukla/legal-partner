package com.legalpartner.service.review;

import com.legalpartner.service.TokenBudgetService;
import com.legalpartner.testsupport.ConfigFixtures;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class DraftVerifierTest {

    private static final String WEAK = "<p class=\"clause-sub\"><strong>1.</strong> The Provider is liable for its breaches.</p>";
    private static final String STRONG = "<p class=\"clause-sub\"><strong>1.</strong> Aggregate liability of each party shall not exceed the fees paid in the prior twelve months.</p>";
    private static final Function<String, String> SANITIZER =
            raw -> "<p class=\"clause-sub\"><strong>1.</strong> " + raw.replaceFirst("^1\\.\\s*", "") + "</p>";

    private static TokenBudgetService budget() {
        TokenBudgetService t = new TokenBudgetService();
        ReflectionTestUtils.setField(t, "modelWindowTokens", 8192);
        return t;
    }

    /** Judge: a requirement is PRESENT only if the clause says "shall not exceed". */
    private static ChatLanguageModel judge() {
        return messages -> {
            String p = ((UserMessage) messages.get(0)).singleText();
            if (!p.contains("Question:")) return Response.from(AiMessage.from("1. provisions"));
            boolean present = p.contains("shall not exceed");
            return Response.from(AiMessage.from(present
                    ? "{\"answer\":\"PRESENT\",\"quote\":\"shall not exceed\"}"
                    : "{\"answer\":\"ABSENT\",\"quote\":\"\"}"));
        };
    }

    private static DraftVerifier verifier(ChatLanguageModel writer, boolean enabled) {
        var spec = ConfigFixtures.clauseSpecs();
        var checker = new SemanticRequirementChecker(judge(), budget(), com.legalpartner.testsupport.ConfigFixtures.prompts());
        return new DraftVerifier(spec, checker, ConfigFixtures.ruleEngine(), writer, ConfigFixtures.prompts(), enabled, 10, 1);
    }

    @Test
    void acceptsRepairThatSatisfiesMoreRequirements() {
        ChatLanguageModel writer = messages -> Response.from(AiMessage.from(
                "1. Aggregate liability of each party shall not exceed the fees paid in the prior twelve months."));
        var out = verifier(writer, true).verifyAndRepair("LIABILITY", WEAK, "Master Services Agreement",
                "MSA", "NEUTRAL", null, SANITIZER);
        assertThat(out.checked()).isGreaterThan(0);
        assertThat(out.repaired()).isTrue();
        assertThat(out.html()).contains("shall not exceed");
    }

    @Test
    void rejectsRepairThatDoesNotImprove() {
        ChatLanguageModel writer = messages -> Response.from(AiMessage.from("1. The Provider remains liable."));
        var out = verifier(writer, true).verifyAndRepair("LIABILITY", WEAK, "MSA", "MSA", "NEUTRAL", null, SANITIZER);
        assertThat(out.repaired()).isFalse();
        assertThat(out.html()).isEqualTo(WEAK);
        assertThat(out.warnings()).isNotEmpty().allSatisfy(w -> assertThat(w).startsWith("Review checklist not met"));
    }

    @Test
    void passingClauseIsUntouched() {
        ChatLanguageModel writer = messages -> { throw new AssertionError("no repair expected"); };
        var out = verifier(writer, true).verifyAndRepair("LIABILITY", STRONG, "MSA", "MSA", "NEUTRAL", null, SANITIZER);
        assertThat(out.html()).isEqualTo(STRONG);
        assertThat(out.unmet()).isEmpty();
    }

    @Test
    void disabledVerifierIsANoOp() {
        ChatLanguageModel writer = messages -> { throw new AssertionError("disabled"); };
        var out = verifier(writer, false).verifyAndRepair("LIABILITY", WEAK, "MSA", "MSA", null, null, SANITIZER);
        assertThat(out.html()).isEqualTo(WEAK);
        assertThat(out.checked()).isZero();
    }

    @Test
    void repairPromptAsksForMinimalNumberedRevision() {
        var reqs = ConfigFixtures.clauseSpecs().semanticRequirements("LIABILITY", "MSA", null, 9);
        String template = ConfigFixtures.prompts().get(DraftVerifier.REPAIR_PROMPT_ID);
        String prompt = DraftVerifier.repairPrompt(template, "MSA", "LIABILITY", WEAK, reqs);
        assertThat(prompt).contains("MINIMALLY").contains("numbered sub-clauses").contains("The Provider is liable");
        reqs.forEach(r -> assertThat(prompt).contains(r.question().question()));
    }
}
