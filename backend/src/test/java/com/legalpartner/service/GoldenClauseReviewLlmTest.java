package com.legalpartner.service;

import com.legalpartner.config.ContractTypeRegistry;
import com.legalpartner.config.ReasoningStrippingChatModel;
import com.legalpartner.rag.HtmlText;
import com.legalpartner.service.review.ClauseSpecRegistry;
import com.legalpartner.service.review.SemanticRequirementChecker;
import com.legalpartner.testsupport.ConfigFixtures;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asks every golden clause the review questions it will face, using the production
 * evaluator. A golden clause that fails its own review is either a weak clause or a
 * bad question — either way it must be fixed before drafts can pass review.
 *
 * Runs only when a model is configured (costs LLM calls):
 * <pre>
 *   LP_EVAL_LLM_URL=https://openrouter.ai/api/v1 LP_EVAL_LLM_MODEL=qwen/qwen3.8-27b \
 *   LP_EVAL_LLM_KEY=sk-... ./gradlew test --tests '*GoldenClauseReviewLlmTest'
 * </pre>
 * Optional: LP_EVAL_TEMPLATES=nda,msa (subset), LP_EVAL_MIN_PASS_RATE=0.9 (fail below).
 * Writes build/reports/golden-review.md.
 */
@EnabledIfEnvironmentVariable(named = "LP_EVAL_LLM_URL", matches = ".+")
class GoldenClauseReviewLlmTest {

    @Test
    void goldenClausesPassTheirOwnReview() throws Exception {
        String url = System.getenv("LP_EVAL_LLM_URL");
        ChatLanguageModel model = ReasoningStrippingChatModel.wrap(OpenAiChatModel.builder()
                .baseUrl(url.endsWith("/v1") ? url : url + "/v1")
                .apiKey(env("LP_EVAL_LLM_KEY", "no-op"))
                .modelName(env("LP_EVAL_LLM_MODEL", "saullm-54b"))
                .temperature(0.0).maxTokens(600).timeout(Duration.ofSeconds(180))
                .build());

        TokenBudgetService budget = new TokenBudgetService();
        ReflectionTestUtils.setField(budget, "modelWindowTokens", 32768);
        SemanticRequirementChecker checker = new SemanticRequirementChecker(model, budget, com.legalpartner.testsupport.ConfigFixtures.prompts());

        GoldenClauseLibrary golden = ConfigFixtures.goldenClauses();
        ContractTypeRegistry contractTypes = ConfigFixtures.contractTypes();
        ClauseSpecRegistry spec = ConfigFixtures.clauseSpecs();

        List<String> only = List.of(env("LP_EVAL_TEMPLATES", "").split(",")).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        int asked = 0;
        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder("# Golden clause review\n\nModel: `")
                .append(env("LP_EVAL_LLM_MODEL", "")).append("`\n\n| Template | Clause | Requirement | Result |\n|---|---|---|---|\n");

        for (String templateId : contractTypes.allTemplateIds()) {
            if (!only.isEmpty() && !only.contains(templateId)) continue;
            var config = contractTypes.get(templateId);
            var deal = GoldenClauseRulesTest.fullDeal(config);
            for (String clauseKey : config.defaultSections()) {
                var clauses = golden.retrieveAll(clauseKey, templateId, "State of Delaware", null);
                if (clauses.isEmpty()) continue;
                var reqs = spec.semanticRequirements(clauseKey, config.reviewType(), "NEUTRAL", 7);
                if (reqs.isEmpty()) continue;
                String text = HtmlText.toPlainText(GoldenClauseRulesTest.render(clauses, deal, golden));
                var results = checker.evaluate(reqs.get(0).reviewKey(), text,
                        reqs.stream().map(ClauseSpecRegistry.SemanticRequirement::question).toList(), null, null);
                Map<String, RiskQuestionEngine.QuestionResult> byId = results.stream()
                        .collect(Collectors.toMap(r -> r.question().id(), r -> r, (a, b) -> a));
                for (var r : reqs) {
                    asked++;
                    boolean ok = r.satisfiedBy(byId.get(r.question().id()));
                    report.append("| ").append(templateId).append(" | ").append(clauseKey).append(" | ")
                          .append(r.question().id()).append(" | ").append(ok ? "pass" : "**FAIL**").append(" |\n");
                    if (!ok) failures.add(templateId + "/" + clauseKey + ": " + r.question().question());
                }
            }
        }

        double passRate = asked == 0 ? 1.0 : 1.0 - (double) failures.size() / asked;
        report.append("\nPass rate: ").append(String.format("%.1f%%", passRate * 100))
              .append(" (").append(asked - failures.size()).append("/").append(asked).append(")\n");
        Path out = Path.of("build/reports/golden-review.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println(report);

        double min = Double.parseDouble(env("LP_EVAL_MIN_PASS_RATE", "0"));
        assertThat(passRate).as("Golden clause review pass rate; failures:\n" + String.join("\n", failures))
                .isGreaterThanOrEqualTo(min);
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
