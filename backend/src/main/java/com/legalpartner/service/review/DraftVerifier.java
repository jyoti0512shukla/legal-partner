package com.legalpartner.service.review;

import com.legalpartner.config.PromptRepository;
import com.legalpartner.model.dto.DealSpec;
import com.legalpartner.rag.HtmlText;
import com.legalpartner.service.ClauseRuleEngine;
import com.legalpartner.service.RiskQuestionEngine.QuestionResult;
import com.legalpartner.service.RiskQuestionEngine.RiskQuestion;
import com.legalpartner.service.review.ClauseSpecRegistry.SemanticRequirement;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Verifies a drafted clause against the review rubric before it ships, and repairs it
 * once if it falls short.
 *
 * Uses {@link SemanticRequirementChecker} — the same evaluator review uses — so a clause
 * that passes here is judged the same way when the finished draft is reviewed.
 *
 * A repair is kept only if it satisfies more requirements AND does not increase the
 * number of failing deterministic (deal-term) rules, so a repair can never trade a
 * review point for a wrong fee or a dropped party name.
 */
@Component
@Slf4j
public class DraftVerifier {

    /** Prompt id in PromptTemplates / clause_prompts.yml. */
    static final String REPAIR_PROMPT_ID = "DRAFT_VERIFY_REPAIR";

    private final ClauseSpecRegistry spec;
    private final SemanticRequirementChecker checker;
    private final ClauseRuleEngine ruleEngine;
    private final ChatLanguageModel chatModel;
    private final PromptRepository prompts;
    private final boolean enabled;
    private final int minWeight;
    private final int maxRepairs;

    public DraftVerifier(ClauseSpecRegistry spec,
                         SemanticRequirementChecker checker,
                         ClauseRuleEngine ruleEngine,
                         ChatLanguageModel chatModel,
                         PromptRepository prompts,
                         @Value("${legalpartner.draft.verify.enabled:true}") boolean enabled,
                         @Value("${legalpartner.draft.verify.min-weight:7}") int minWeight,
                         @Value("${legalpartner.draft.verify.max-repairs:1}") int maxRepairs) {
        this.spec = spec;
        this.checker = checker;
        this.ruleEngine = ruleEngine;
        this.chatModel = chatModel;
        this.prompts = prompts;
        this.enabled = enabled;
        this.minWeight = minWeight;
        this.maxRepairs = Math.max(0, maxRepairs);
    }

    public boolean isEnabled() { return enabled; }

    public int minWeight() { return minWeight; }

    /**
     * @param html     final clause HTML (repaired if a repair was accepted)
     * @param checked  number of review requirements checked
     * @param unmet    requirements still not satisfied
     */
    public record Outcome(String html, int checked, List<SemanticRequirement> unmet,
                          boolean repaired, List<String> warnings) {
        static Outcome unchanged(String html) {
            return new Outcome(html, 0, List.of(), false, List.of());
        }
    }

    /** @param sanitizer turns raw LLM text into clause HTML (DraftService's sanitizer) */
    public Outcome verifyAndRepair(String clauseKey, String html, String contractTypeName,
                                   String reviewType, String clientPosition, DealSpec dealSpec,
                                   Function<String, String> sanitizer) {
        if (!enabled || html == null || html.isBlank()) return Outcome.unchanged(html);

        List<SemanticRequirement> reqs = spec.semanticRequirements(clauseKey, reviewType, clientPosition, minWeight);
        if (reqs.isEmpty()) return Outcome.unchanged(html);

        List<SemanticRequirement> unmet = unmet(clauseKey, html, reqs);
        log.info("Draft verify [{}]: {}/{} review requirements met", clauseKey, reqs.size() - unmet.size(), reqs.size());
        if (unmet.isEmpty()) return new Outcome(html, reqs.size(), List.of(), false, List.of());

        String best = html;
        boolean repaired = false;
        for (int attempt = 1; attempt <= maxRepairs && !unmet.isEmpty(); attempt++) {
            String candidate;
            try {
                String raw = chatModel.generate(UserMessage.from(
                        repairPrompt(prompts.get(REPAIR_PROMPT_ID), contractTypeName, clauseKey, best, unmet)))
                        .content().text();
                candidate = sanitizer.apply(raw == null ? "" : raw.trim());
            } catch (Exception e) {
                log.warn("Draft verify [{}]: repair call failed: {}", clauseKey, e.getMessage());
                break;
            }
            if (candidate == null || candidate.isBlank()) continue;

            List<SemanticRequirement> candidateUnmet = unmet(clauseKey, candidate, reqs);
            int rulesBefore = failingRules(best, clauseKey, dealSpec);
            int rulesAfter = failingRules(candidate, clauseKey, dealSpec);
            if (candidateUnmet.size() < unmet.size() && rulesAfter <= rulesBefore) {
                log.info("Draft verify [{}]: repair {} accepted ({} → {} unmet, rule failures {} → {})",
                        clauseKey, attempt, unmet.size(), candidateUnmet.size(), rulesBefore, rulesAfter);
                best = candidate;
                unmet = candidateUnmet;
                repaired = true;
            } else {
                log.info("Draft verify [{}]: repair {} rejected ({} → {} unmet, rule failures {} → {})",
                        clauseKey, attempt, unmet.size(), candidateUnmet.size(), rulesBefore, rulesAfter);
            }
        }

        List<String> warnings = unmet.stream()
                .map(r -> "Review checklist not met: " + r.question().question())
                .collect(Collectors.toList());
        return new Outcome(best, reqs.size(), unmet, repaired, warnings);
    }

    private List<SemanticRequirement> unmet(String clauseKey, String html, List<SemanticRequirement> reqs) {
        List<RiskQuestion> questions = reqs.stream().map(SemanticRequirement::question).toList();
        String label = reqs.get(0).reviewKey();
        // clientPosition already applied when selecting requirements — pass null here.
        List<QuestionResult> results = checker.evaluate(label, HtmlText.toPlainText(html), questions, null, null);
        Map<String, QuestionResult> byId = results.stream()
                .collect(Collectors.toMap(r -> r.question().id(), r -> r, (a, b) -> a));
        List<SemanticRequirement> out = new ArrayList<>();
        for (SemanticRequirement r : reqs) {
            if (!r.satisfiedBy(byId.get(r.question().id()))) out.add(r);
        }
        return out;
    }

    private int failingRules(String html, String clauseKey, DealSpec dealSpec) {
        if (dealSpec == null) return 0;
        try {
            return (int) ruleEngine.validate(html, clauseKey, dealSpec).stream().filter(r -> !r.passed()).count();
        } catch (Exception e) {
            return 0;
        }
    }

    static String repairPrompt(String template, String contractTypeName, String clauseKey, String html,
                               List<SemanticRequirement> unmet) {
        StringBuilder points = new StringBuilder();
        for (SemanticRequirement r : unmet) points.append("- ").append(r.instruction()).append("\n");
        return String.format(template, clauseKey,
                contractTypeName == null ? "commercial" : contractTypeName,
                points, HtmlText.toPlainText(html));
    }
}
