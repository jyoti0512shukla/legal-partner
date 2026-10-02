package com.legalpartner.service.review;

import com.legalpartner.service.RiskQuestionEngine.RiskQuestion;
import com.legalpartner.testsupport.ConfigFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClauseSpecRegistryTest {

    private final ClauseSpecRegistry spec = ConfigFixtures.clauseSpecs();

    @Test
    void mapsTemplatesToReviewTypes() {
        assertThat(spec.reviewType("software_license")).isEqualTo("SOFTWARE_LICENSE");
        assertThat(spec.reviewType("saas")).isEqualTo("SAAS");
        assertThat(spec.reviewType("fintech_msa")).isEqualTo("MSA");
        assertThat(spec.reviewType("nda")).isEqualTo("NDA");
    }

    @Test
    void mapsDraftingKeysToReviewKeys() {
        assertThat(spec.reviewKeysFor("LIABILITY")).containsExactly("LIABILITY");
        assertThat(spec.reviewKeysFor("REPRESENTATIONS_WARRANTIES")).containsExactly("WARRANTIES");
        assertThat(spec.reviewKeysFor("SERVICES")).containsExactly("SLA");
        assertThat(spec.reviewKeysFor("DEFINITIONS")).isEmpty();
        assertThat(spec.reviewKeysFor("NOT_A_CLAUSE")).isEmpty();
    }

    @Test
    void semanticRequirementsComeFromReviewQuestions() {
        var reqs = spec.semanticRequirements("LIABILITY", "MSA", "NEUTRAL", 7);
        assertThat(reqs).isNotEmpty();
        assertThat(reqs).extracting(r -> r.question().id()).contains("liability_cap_exists", "liability_cap_mutual");
        assertThat(reqs).allSatisfy(r -> assertThat(r.question().weight()).isGreaterThanOrEqualTo(7));
    }

    @Test
    void oneSidedDraftDropsPositionSensitiveRequirements() {
        var reqs = spec.semanticRequirements("LIABILITY", "MSA", "PARTY_A", 7);
        assertThat(reqs).extracting(r -> r.question().id()).doesNotContain("liability_cap_mutual");
    }

    @Test
    void expectedAnswerFollowsRiskDirection() {
        assertThat(ClauseSpecRegistry.expectedAnswer(new RiskQuestion("a", "q", "HIGH", "LOW", "c", List.of(), 9))).isEqualTo("YES");
        assertThat(ClauseSpecRegistry.expectedAnswer(new RiskQuestion("a", "q", "INFO", "MEDIUM", "c", List.of(), 5))).isEqualTo("NO");
        assertThat(ClauseSpecRegistry.expectedAnswer(new RiskQuestion("a", "q", "LOW", "LOW", "c", List.of(), 4))).isNull();
    }

    @Test
    void reviewSectionsComeFromManifest() {
        var manifest = new DraftManifest(1, "software_license", "SOFTWARE_LICENSE", "PARTY_A", "FIRST_DRAFT", "Delaware",
                List.of(new DraftManifest.Section("DEFINITIONS", "Definitions", 1, List.of(), "defs"),
                        new DraftManifest.Section("LIABILITY", "Liability", 2, List.of("LIABILITY"), "cap text"),
                        new DraftManifest.Section("REPRESENTATIONS_WARRANTIES", "Reps", 3, null, "warranty text")));
        var sections = spec.reviewSections(manifest);
        assertThat(sections).containsOnlyKeys("LIABILITY", "WARRANTIES");
        assertThat(sections.get("LIABILITY")).startsWith("ARTICLE 2").contains("cap text");
    }

    @Test
    void requirementsPromptListsEachRequirement() {
        var reqs = spec.semanticRequirements("LIABILITY", "MSA", null, 9);
        String prompt = spec.requirementsPrompt(reqs);
        assertThat(prompt).contains("REVIEW CHECKLIST");
        reqs.forEach(r -> assertThat(prompt).contains(r.question().question()));
        assertThat(spec.requirementsPrompt(List.of())).isEmpty();
    }
}
