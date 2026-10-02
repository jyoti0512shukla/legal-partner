package com.legalpartner.service.review;

import com.legalpartner.config.ClauseTypeRegistry;
import com.legalpartner.config.ContractTypeRegistry;
import com.legalpartner.model.dto.DealSpec;
import com.legalpartner.service.ClauseRuleEngine;
import com.legalpartner.service.RiskQuestionEngine;
import com.legalpartner.service.RiskQuestionEngine.QuestionResult;
import com.legalpartner.service.RiskQuestionEngine.RiskQuestion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One logical clause spec shared by drafting and review.
 *
 * Per clause type it combines:
 * <ul>
 *   <li><b>Semantic requirements</b> — the review questions ({@code risk_questions.yml}).
 *       Review asks them; drafting is prompted with them and verified against them.</li>
 *   <li><b>Deterministic requirements</b> — deal-bound drafting rules
 *       ({@code clause_requirements.yml}); drafting-only.</li>
 *   <li><b>Key mapping</b> — drafting clause keys → review clause keys
 *       ({@code clauses.yml review_keys}).</li>
 *   <li><b>Contract-type mapping</b> — template → review type
 *       ({@code contract_types.yml review_type}).</li>
 * </ul>
 *
 * The YAML files stay separate for now (they hold different kinds of checks and have
 * in-flight edits); this class is the only place that joins them, so a later physical
 * consolidation into one file changes nothing for callers.
 */
@Component
public class ClauseSpecRegistry {

    private static final Set<String> MATERIAL = Set.of("HIGH", "MEDIUM");

    private final ClauseTypeRegistry clauseTypes;
    private final ContractTypeRegistry contractTypes;
    private final RiskQuestionEngine riskQuestions;
    private final ClauseRuleEngine ruleEngine;

    public ClauseSpecRegistry(ClauseTypeRegistry clauseTypes, ContractTypeRegistry contractTypes,
                              RiskQuestionEngine riskQuestions, ClauseRuleEngine ruleEngine) {
        this.clauseTypes = clauseTypes;
        this.contractTypes = contractTypes;
        this.riskQuestions = riskQuestions;
        this.ruleEngine = ruleEngine;
    }

    /**
     * A review question seen as a drafting requirement.
     *
     * @param expectedAnswer "YES" when the provision must be present (risk if NO),
     *                       "NO" when it must be absent (risk if YES)
     */
    public record SemanticRequirement(RiskQuestion question, String reviewKey, String expectedAnswer) {

        public boolean satisfiedBy(QuestionResult r) {
            if (r == null || !r.answered() || r.answer() == null) return false;
            if (RiskQuestionEngine.isWaived(r)) return true;
            return r.answer().trim().toUpperCase().startsWith(expectedAnswer);
        }

        /** Imperative phrasing for prompts ("The clause must address: …"). */
        public String instruction() {
            return ("YES".equals(expectedAnswer) ? "Must address: " : "Must NOT include: ") + question.question();
        }
    }

    // ── Contract types ──────────────────────────────────────────────────────

    /** Review contract-type code for a drafting template (e.g. software_license → SOFTWARE_LICENSE). */
    public String reviewType(String templateId) {
        return contractTypes.reviewType(templateId);
    }

    public List<String> requiredReviewClauses(String reviewType) {
        return riskQuestions.getRequiredClauses(reviewType);
    }

    // ── Key mapping ─────────────────────────────────────────────────────────

    public List<String> reviewKeysFor(String draftingClauseKey) {
        if (draftingClauseKey == null || !clauseTypes.contains(draftingClauseKey)) return List.of();
        return clauseTypes.get(draftingClauseKey).reviewKeys();
    }

    /**
     * Drafting clause key for a review clause key (inverse of {@link #reviewKeysFor}):
     * a clause type with the same key wins, otherwise the first clause type reviewed as it.
     */
    public java.util.Optional<String> draftingKeyFor(String reviewKey) {
        if (reviewKey == null) return java.util.Optional.empty();
        if (clauseTypes.contains(reviewKey) && clauseTypes.get(reviewKey).reviewKeys().contains(reviewKey)) {
            return java.util.Optional.of(reviewKey);
        }
        return clauseTypes.allKeys().stream()
                .filter(k -> clauseTypes.get(k).reviewKeys().contains(reviewKey))
                .findFirst();
    }

    /** Review clause keys covered by a template's default sections. */
    public Set<String> reviewKeysCoveredBy(List<String> draftingClauseKeys) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String k : draftingClauseKeys) out.addAll(reviewKeysFor(k));
        return out;
    }

    /**
     * Review clause key → text, built from a draft manifest. Sections mapping to the same
     * review key are concatenated in article order.
     */
    public Map<String, String> reviewSections(DraftManifest manifest) {
        Map<String, String> out = new LinkedHashMap<>();
        if (manifest == null || manifest.sections() == null) return out;
        for (DraftManifest.Section s : manifest.sections()) {
            List<String> keys = (s.reviewKeys() != null && !s.reviewKeys().isEmpty())
                    ? s.reviewKeys() : reviewKeysFor(s.key());
            String body = "ARTICLE " + s.article() + " — " + s.title() + "\n" + (s.text() == null ? "" : s.text());
            for (String rk : keys) {
                out.merge(rk, body, (a, b) -> a + "\n\n" + b);
            }
        }
        return out;
    }

    // ── Requirements ────────────────────────────────────────────────────────

    /**
     * Semantic requirements a drafted clause is held to: the review questions for its
     * review keys and contract type, excluding waived (position-sensitive) questions,
     * informational questions, and those below {@code minWeight}.
     */
    public List<SemanticRequirement> semanticRequirements(String draftingClauseKey, String reviewType,
                                                          String clientPosition, int minWeight) {
        List<SemanticRequirement> out = new ArrayList<>();
        for (String rk : reviewKeysFor(draftingClauseKey)) {
            for (RiskQuestion q : riskQuestions.getQuestionsForClause(rk, reviewType)) {
                if (q.weight() < minWeight) continue;
                if (RiskQuestionEngine.isWaivedFor(q, clientPosition)) continue;
                String expected = expectedAnswer(q);
                if (expected != null) out.add(new SemanticRequirement(q, rk, expected));
            }
        }
        return out;
    }

    /** YES if missing it is risky, NO if having it is risky, null if neither answer matters. */
    static String expectedAnswer(RiskQuestion q) {
        boolean riskyIfNo = q.riskIfNo() != null && MATERIAL.contains(q.riskIfNo());
        boolean riskyIfYes = q.riskIfYes() != null && MATERIAL.contains(q.riskIfYes());
        if (riskyIfNo && !riskyIfYes) return "YES";
        if (riskyIfYes && !riskyIfNo) return "NO";
        return null;
    }

    public List<ClauseRuleEngine.ClauseRule> deterministicRequirements(String draftingClauseKey, DealSpec dealSpec) {
        return ruleEngine.getRulesForClause(draftingClauseKey, dealSpec);
    }

    /** Prompt block listing the semantic requirements, appended to the clause generation prompt. */
    public String requirementsPrompt(List<SemanticRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nREVIEW CHECKLIST — this clause will be reviewed against these points; satisfy each one explicitly:\n");
        for (SemanticRequirement r : requirements) {
            sb.append("- ").append(r.instruction()).append("\n");
        }
        return sb.toString();
    }
}
