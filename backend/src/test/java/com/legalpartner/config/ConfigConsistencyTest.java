package com.legalpartner.config;

import com.legalpartner.model.dto.DealSpec;
import com.legalpartner.service.ClauseRuleEngine;
import com.legalpartner.service.GoldenClauseLibrary;
import com.legalpartner.service.RiskQuestionEngine;
import com.legalpartner.service.review.ClauseSpecRegistry;
import com.legalpartner.testsupport.ConfigFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-file invariants for the YAML configs. Each test lists every violation so a
 * failure says exactly which file and key drifted.
 *
 * These are the checks that keep drafting and review on one rubric: a template must
 * produce every clause its review type requires, and every field the intake form asks
 * for must exist on DealSpec.
 */
class ConfigConsistencyTest {

    private final ContractTypeRegistry contractTypes = ConfigFixtures.contractTypes();
    private final ClauseTypeRegistry clauseTypes = ConfigFixtures.clauseTypes();
    private final RiskQuestionEngine riskQuestions = ConfigFixtures.riskQuestions();
    private final ClauseRuleEngine ruleEngine = ConfigFixtures.ruleEngine();
    private final GoldenClauseLibrary golden = ConfigFixtures.goldenClauses();
    private final ClauseSpecRegistry spec =
            new ClauseSpecRegistry(clauseTypes, contractTypes, riskQuestions, ruleEngine);

    private Set<String> knownReviewTypes() {
        Set<String> out = new LinkedHashSet<>(riskQuestions.getKnownContractTypes());
        for (String t : contractTypes.allTemplateIds()) out.add(contractTypes.reviewType(t));
        return out;
    }

    @Test
    void intakeFieldPathsExistOnDealSpec() {
        List<String> violations = new ArrayList<>();
        for (String t : contractTypes.allTemplateIds()) {
            var c = contractTypes.get(t);
            for (String path : concat(c.requiredFields(), c.recommendedFields())) {
                if (!resolvesOnDealSpec(path)) {
                    violations.add("contract_types.yml " + t + ": '" + path + "' is not a DealSpec field");
                }
            }
        }
        assertThat(violations).as("Intake fields that can never be satisfied").isEmpty();
    }

    @Test
    void everyTemplateHasAKnownReviewType() {
        List<String> violations = new ArrayList<>();
        Set<String> known = riskQuestions.getKnownContractTypes();
        for (String t : contractTypes.allTemplateIds()) {
            String rt = contractTypes.reviewType(t);
            if (rt == null || !known.contains(rt)) {
                violations.add("contract_types.yml " + t + ": review_type '" + rt
                        + "' has no required_clauses entry in risk_questions.yml");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void draftsProduceEveryClauseTheirReviewRequires() {
        List<String> violations = new ArrayList<>();
        for (String t : contractTypes.allTemplateIds()) {
            String rt = contractTypes.reviewType(t);
            Set<String> covered = spec.reviewKeysCoveredBy(contractTypes.defaultSections(t));
            for (String required : spec.requiredReviewClauses(rt)) {
                if (!covered.contains(required)) {
                    violations.add(t + " (review_type " + rt + ") requires " + required
                            + " but its default_sections never produce it");
                }
            }
        }
        assertThat(violations).as("Drafts that would be flagged MISSING by our own review").isEmpty();
    }

    @Test
    void defaultSectionsAreKnownClauseTypes() {
        List<String> violations = new ArrayList<>();
        for (String t : contractTypes.allTemplateIds()) {
            for (String k : contractTypes.defaultSections(t)) {
                if (!clauseTypes.contains(k)) violations.add("contract_types.yml " + t + ": unknown clause " + k);
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void reviewKeysExistInRiskQuestions() {
        Set<String> reviewClauseKeys = riskQuestions.getAllClauseTypes();
        List<String> violations = new ArrayList<>();
        for (String k : clauseTypes.allKeys()) {
            for (String rk : clauseTypes.get(k).reviewKeys()) {
                if (!reviewClauseKeys.contains(rk)) {
                    violations.add("clauses.yml " + k + ": review key " + rk + " has no questions in risk_questions.yml");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void inventoryKeywordsCoverExactlyTheReviewClauses() {
        Set<String> keywordKeys = riskQuestions.getClauseKeywords().keySet();
        Set<String> reviewKeys = riskQuestions.getAllClauseTypes();
        assertThat(keywordKeys).as("clause_keywords keys vs clause_questions keys in risk_questions.yml")
                .containsExactlyInAnyOrderElementsOf(reviewKeys);
    }

    @Test
    void defaultRequiredClausesAreDefinedInYaml() {
        // No code fallback exists — the engine refuses to start without it.
        assertThat(riskQuestions.getRequiredClauses("SOME_UNKNOWN_TYPE")).isNotEmpty();
    }

    @Test
    void riskQuestionAppliesToUsesKnownReviewTypes() {
        Set<String> known = knownReviewTypes();
        List<String> violations = new ArrayList<>();
        for (String clause : riskQuestions.getAllClauseTypes()) {
            for (var q : riskQuestions.getQuestionsForClause(clause, null)) {
                for (String at : q.appliesTo()) {
                    if (!known.contains(at)) {
                        violations.add("risk_questions.yml " + q.id() + ": applies_to '" + at + "' is not a review type");
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void goldenClausesReferenceKnownClauseAndContractTypes() {
        Set<String> templates = contractTypes.allTemplateIds();
        List<String> violations = new ArrayList<>();
        for (var g : golden.all()) {
            if (!clauseTypes.contains(g.clauseType())) {
                violations.add("golden_clauses.yml " + g.id() + ": unknown clause type " + g.clauseType());
            }
            if (!"default".equals(g.contractType()) && !templates.contains(g.contractType())) {
                violations.add("golden_clauses.yml " + g.id() + ": unknown contract type " + g.contractType());
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void rulesApplyToKnownClauseTypes() {
        List<String> violations = new ArrayList<>();
        for (var r : ruleEngine.allRules()) {
            for (String k : r.appliesTo()) {
                if (!"ALL".equals(k) && !clauseTypes.contains(k)) {
                    violations.add("clause_requirements.yml " + r.id() + ": unknown clause " + k);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out;
    }

    static boolean resolvesOnDealSpec(String path) {
        String[] parts = path.split("\\.");
        if (parts.length != 2) return false;
        try {
            Field section = DealSpec.class.getDeclaredField(parts[0]);
            section.getType().getDeclaredField(parts[1]);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
