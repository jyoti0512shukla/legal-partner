package com.legalpartner.testsupport;

import com.legalpartner.config.ClauseTypeRegistry;
import com.legalpartner.config.ContractTypeRegistry;
import com.legalpartner.config.PromptRepository;
import com.legalpartner.service.ClauseRuleEngine;
import com.legalpartner.service.GoldenClauseLibrary;
import com.legalpartner.service.RiskQuestionEngine;
import com.legalpartner.service.review.ClauseSpecRegistry;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds the real YAML-backed registries without a Spring context, so config tests
 * exercise exactly the files that ship.
 */
public final class ConfigFixtures {

    private ConfigFixtures() {}

    public static PromptRepository prompts() {
        PromptRepository p = new PromptRepository();
        ReflectionTestUtils.invokeMethod(p, "load");
        return p;
    }

    public static ClauseTypeRegistry clauseTypes() {
        ClauseTypeRegistry c = new ClauseTypeRegistry();
        ReflectionTestUtils.setField(c, "promptRepository", prompts());
        ReflectionTestUtils.invokeMethod(c, "load");
        return c;
    }

    public static ContractTypeRegistry contractTypes() {
        ContractTypeRegistry c = new ContractTypeRegistry();
        ReflectionTestUtils.invokeMethod(c, "load");
        return c;
    }

    public static RiskQuestionEngine riskQuestions() {
        RiskQuestionEngine r = new RiskQuestionEngine();
        r.init();
        return r;
    }

    public static ClauseRuleEngine ruleEngine() {
        ClauseRuleEngine r = new ClauseRuleEngine();
        ReflectionTestUtils.invokeMethod(r, "load");
        return r;
    }

    public static GoldenClauseLibrary goldenClauses() {
        GoldenClauseLibrary g = new GoldenClauseLibrary();
        ReflectionTestUtils.invokeMethod(g, "load");
        return g;
    }

    public static com.legalpartner.config.LegalVocabulary vocabulary() {
        var v = new com.legalpartner.config.LegalVocabulary();
        ReflectionTestUtils.invokeMethod(v, "load");
        return v;
    }

    public static com.legalpartner.service.learning.LearningConfig learning() {
        var l = new com.legalpartner.service.learning.LearningConfig();
        ReflectionTestUtils.invokeMethod(l, "load");
        return l;
    }

    public static ClauseSpecRegistry clauseSpecs() {
        return new ClauseSpecRegistry(clauseTypes(), contractTypes(), riskQuestions(), ruleEngine());
    }
}
