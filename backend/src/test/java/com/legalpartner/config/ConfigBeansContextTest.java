package com.legalpartner.config;

import com.legalpartner.rag.LegalDocumentChunker;
import com.legalpartner.rag.QueryExpander;
import com.legalpartner.service.ClauseRuleEngine;
import com.legalpartner.service.GoldenClauseLibrary;
import com.legalpartner.service.RiskQuestionEngine;
import com.legalpartner.service.TemplateService;
import com.legalpartner.service.extraction.ContractTypeDetector;
import com.legalpartner.service.review.ClauseSpecRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring wiring smoke test for every YAML-backed bean (no database, no LLM): constructor
 * injection, @PostConstruct loading and @ConfigurationProperties binding all succeed.
 */
@SpringJUnitConfig(ConfigBeansContextTest.Beans.class)
@TestPropertySource(properties = "legalpartner.legal-system=USA")
class ConfigBeansContextTest {

    @Configuration
    @EnableConfigurationProperties(LegalSystemConfig.class)
    @Import({PromptRepository.class, ClauseTypeRegistry.class, ContractTypeRegistry.class, RiskQuestionEngine.class,
            ClauseRuleEngine.class, GoldenClauseLibrary.class, DraftingDefaults.class, LegalVocabulary.class,
            ClauseSpecRegistry.class, QueryExpander.class, ContractTypeDetector.class, LegalDocumentChunker.class,
            TemplateService.class, PartyNameVariantsConfig.class})
    static class Beans {}

    @Autowired LegalSystemConfig legalSystem;
    @Autowired ClauseSpecRegistry spec;
    @Autowired TemplateService templates;
    @Autowired ContractTypeDetector detector;
    @Autowired DraftingDefaults defaults;

    @Test
    void configBeansWireAndLoad() {
        assertThat(legalSystem.getLegalSystem()).isEqualTo("USA");
        assertThat(legalSystem.country()).isEqualTo("United States");
        assertThat(spec.reviewType("saas")).isEqualTo("SAAS");
        assertThat(templates.listTemplates()).isNotEmpty();
        assertThat(detector.detect("This Mutual NDA ... confidential information").contractType()).isEqualTo("NDA");
        assertThat(defaults.form("TERM_YEARS")).isEqualTo("3");
    }
}
