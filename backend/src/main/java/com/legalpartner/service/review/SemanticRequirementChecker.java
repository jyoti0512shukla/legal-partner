package com.legalpartner.service.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.PromptRepository;
import com.legalpartner.service.RiskQuestionEngine;
import com.legalpartner.service.RiskQuestionEngine.QuestionResult;
import com.legalpartner.service.RiskQuestionEngine.RiskQuestion;
import com.legalpartner.service.TokenBudgetService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Answers semantic requirements (risk questions) against one clause's text.
 *
 * The single evaluator for both sides of the round trip: review uses it to score a
 * contract, drafting uses it to verify a clause before it ships. Using the same method
 * on both sides means a draft that passes verification cannot fail review because of a
 * difference in how the question was asked.
 *
 * Two-stage, per-question evaluation:
 *   Stage 1: pre-extract all provisions from the clause (one LLM call).
 *   Stage 2: for each question, match against the clause + extracted provisions (one call each).
 *
 * Why not batch: batching 8+ questions causes a "lazy NO cascade" where the model
 * defaults to NO for all questions; quantization compounds this. Requiring evidence
 * before answering reduces false negatives.
 */
@Component
@Slf4j
public class SemanticRequirementChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CLAUSE_OUTPUT_TOKENS = 400;

    private final ChatLanguageModel riskChatModel;
    private final TokenBudgetService tokenBudget;
    private final PromptRepository prompts;
    /** Question id → this firm's latest corrections for it (few-shot), or "". */
    private final java.util.function.Function<String, String> corrections;

    public SemanticRequirementChecker(ChatLanguageModel riskChatModel, TokenBudgetService tokenBudget,
                                      PromptRepository prompts) {
        this(riskChatModel, tokenBudget, prompts, id -> "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SemanticRequirementChecker(@Qualifier("riskChatModel") ChatLanguageModel riskChatModel,
                                      TokenBudgetService tokenBudget,
                                      PromptRepository prompts,
                                      com.legalpartner.service.learning.ReviewCalibrationService calibration) {
        this(riskChatModel, tokenBudget, prompts, calibration::correctionsFor);
    }

    SemanticRequirementChecker(ChatLanguageModel riskChatModel, TokenBudgetService tokenBudget,
                               PromptRepository prompts, java.util.function.Function<String, String> corrections) {
        this.riskChatModel = riskChatModel;
        this.tokenBudget = tokenBudget;
        this.prompts = prompts;
        this.corrections = corrections;
    }

    /**
     * @param clientPosition PARTY_A / PARTY_B / NEUTRAL / null — position-sensitive
     *                       questions are recorded as {@link RiskQuestionEngine#WAIVED}
     *                       without an LLM call when the document favours one party.
     * @param onResult       optional listener called as each answer completes (SSE streaming)
     */
    public List<QuestionResult> evaluate(String clauseType, String clauseText,
                                         List<RiskQuestion> questions, String clientPosition,
                                         Consumer<QuestionResult> onResult) {
        List<QuestionResult> results = new ArrayList<>();
        if (questions == null || questions.isEmpty()) return results;

        List<RiskQuestion> toAsk = new ArrayList<>();
        for (RiskQuestion q : questions) {
            if (RiskQuestionEngine.isWaivedFor(q, clientPosition)) {
                QuestionResult waived = new QuestionResult(q, true, RiskQuestionEngine.WAIVED,
                        "Not applicable: drafted in favour of " + clientPosition
                                + " — one-sided terms are intentional.");
                results.add(waived);
                notify(onResult, waived);
            } else {
                toAsk.add(q);
            }
        }
        if (toAsk.isEmpty()) return results;

        String truncated = fitToBudget(clauseText);
        String provisions = extractProvisions(clauseType, truncated);

        for (RiskQuestion q : toAsk) {
            QuestionResult qr = askOne(clauseType, truncated, provisions, q);
            results.add(qr);
            notify(onResult, qr);
        }

        long yes = results.stream().filter(r -> "YES".equals(r.answer())).count();
        long no = results.stream().filter(r -> "NO".equals(r.answer())).count();
        log.info("Semantic check [{}]: {} questions → {} YES, {} NO, {} waived",
                clauseType, results.size(), yes, no, results.size() - toAsk.size());
        return results;
    }

    private String fitToBudget(String clauseText) {
        String system = "You are a legal analyst.";
        int systemTokens = tokenBudget.countTokensCached("sys:" + system.hashCode(), system);
        int available = tokenBudget.availableContentTokens(systemTokens, CLAUSE_OUTPUT_TOKENS, 50);
        return tokenBudget.fitToTokenBudget(clauseText, available, null);
    }

    private String extractProvisions(String clauseType, String clauseText) {
        try {
            String prompt = String.format(prompts.get("SEMANTIC_PROVISION_EXTRACTION"), clauseType, clauseText);
            AiMessage response = riskChatModel.generate(UserMessage.from(prompt)).content();
            String provisions = response.text().trim();
            log.debug("Semantic check [{}]: extracted {} chars of provisions", clauseType, provisions.length());
            return provisions;
        } catch (Exception e) {
            log.warn("Provision pre-extraction failed for {}: {}", clauseType, e.getMessage());
            return "";
        }
    }

    private QuestionResult askOne(String clauseType, String clauseText, String provisions, RiskQuestion q) {
        try {
            String questionPrompt = String.format(prompts.get("SEMANTIC_REQUIREMENT_QUESTION"), clauseType, clauseText,
                    provisions.length() > 50 ? provisions : "(no provisions extracted)",
                    q.question() + correctionsFor(q.id()));

            String raw = riskChatModel.generate(UserMessage.from(questionPrompt)).content().text().trim();
            return parseAnswer(q, raw);
        } catch (Exception e) {
            log.warn("Semantic question {} failed for clause {}: {}", q.id(), clauseType, e.getMessage());
            return new QuestionResult(q, false, null, null);
        }
    }

    private String correctionsFor(String questionId) {
        try {
            return corrections.apply(questionId);
        } catch (Exception e) {
            log.debug("Corrections for {} unavailable: {}", questionId, e.getMessage());
            return "";
        }
    }

    /** PRESENT → YES, anything else → NO. Package-private for tests. */
    static QuestionResult parseAnswer(RiskQuestion q, String raw) {
        String answer = "NO";
        String quote = "";
        try {
            int jsonStart = raw.indexOf('{');
            int jsonEnd = raw.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JsonNode node = MAPPER.readTree(raw.substring(jsonStart, jsonEnd + 1));
                String a = node.path("answer").asText("ABSENT").toUpperCase();
                answer = "PRESENT".equals(a) ? "YES" : "NO";
                quote = node.path("quote").asText("");
            } else {
                answer = raw.toUpperCase().contains("PRESENT") ? "YES" : "NO";
            }
        } catch (Exception parseEx) {
            answer = raw.toUpperCase().contains("PRESENT") ? "YES" : "NO";
        }
        return new QuestionResult(q, true, answer, quote);
    }

    private static void notify(Consumer<QuestionResult> onResult, QuestionResult qr) {
        if (onResult == null) return;
        try { onResult.accept(qr); } catch (Exception ignored) { }
    }
}
