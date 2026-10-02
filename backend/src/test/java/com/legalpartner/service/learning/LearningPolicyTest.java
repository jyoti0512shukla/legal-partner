package com.legalpartner.service.learning;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.learning.FirmClause;
import com.legalpartner.model.enums.ContractStatus;
import com.legalpartner.testsupport.ConfigFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** learning.yml is consistent and the services apply it (no policy left in code). */
class LearningPolicyTest {

    private static final LearningConfig CONFIG = ConfigFixtures.learning();

    @Test
    void stanceOrdersOnlyUseDeclaredPositions() {
        assertThat(CONFIG.getPositions()).isNotEmpty();
        CONFIG.getStancePositionOrder().forEach((stance, order) ->
                assertThat(CONFIG.getPositions()).as(stance).containsAll(order));
        assertThat(CONFIG.positionOrder("UNKNOWN_STANCE")).isEqualTo(CONFIG.positionOrder(null));
    }

    @Test
    void everyConfiguredStanceHasDraftingPromptText() {
        var prompts = ConfigFixtures.prompts();
        CONFIG.getStancePositionOrder().keySet().stream().filter(k -> !k.startsWith("_"))
                .forEach(stance -> assertThat(prompts.contains("DRAFT_STANCE_" + stance)).as(stance).isTrue());
    }

    @Test
    void everyNormRuleReadsARealDocumentProperty() {
        BeanWrapperImpl bean = new BeanWrapperImpl(DocumentMetadata.class);
        for (var r : CONFIG.getNormRules()) {
            assertThat(bean.isReadableProperty(r.field())).as("DocumentMetadata." + r.field()).isTrue();
            assertThat(r.kind()).isIn("numeric", "categorical");
            assertThat(r.message()).doesNotContain("%s");
        }
    }

    @Test
    void thresholdsAreSane() {
        assertThat(CONFIG.getHelpfulEditRatio()).isLessThan(CONFIG.getHarmfulEditRatio());
        assertThat(CONFIG.getMiningSimilarity()).isBetween(0.0, 1.0);
        assertThat(CONFIG.getMiningMinClauseChars()).isLessThan(CONFIG.getMiningMaxClauseChars());
        assertThat(CONFIG.getCalibrationMinMultiplier()).isBetween(0.0, 1.0);
    }

    @Test
    void sourceAndSignaturePolicy() {
        assertThat(CONFIG.isFirmPrecedent("USER", null)).isTrue();
        assertThat(CONFIG.isFirmPrecedent("EDGAR", ContractStatus.EXECUTED)).isFalse();
        assertThat(CONFIG.isFirmPrecedent("draft_async", null)).isFalse();
        assertThat(CONFIG.isFirmPrecedent("DRAFTED", ContractStatus.EXECUTED)).isTrue();
    }

    @Test
    void finalOfferPrefersFallbacks() {
        FirmClause primary = clause("PRIMARY");
        FirmClause fb1 = clause("FALLBACK_1");
        assertThat(FirmClauseBankService.choose(List.of(primary, fb1), CONFIG.positionOrder("FIRST_DRAFT"))).contains(primary);
        assertThat(FirmClauseBankService.choose(List.of(primary, fb1), CONFIG.positionOrder("FINAL_OFFER"))).contains(fb1);
    }

    @Test
    void normDeviationsFollowRulesAndMessages() {
        List<DocumentMetadata> signed = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            DocumentMetadata d = new DocumentMetadata();
            d.setNoticePeriodDays(30);
            d.setGoverningLawJurisdiction("Delaware");
            signed.add(d);
        }
        var norms = FirmNormsService.compute("SAAS", signed, CONFIG.getNormRules());
        assertThat(norms.byField().get("noticePeriodDays").median()).isEqualTo(30);

        DocumentMetadata doc = new DocumentMetadata();
        doc.setNoticePeriodDays(90);
        doc.setGoverningLawJurisdiction("New York");
        List<String> notes = FirmNormsService.deviations(doc, norms, CONFIG.getNormRules(), CONFIG.getNormsMinSupport());
        assertThat(notes).hasSize(2);
        assertThat(notes.get(0)).contains("90").contains("30").contains("6 signed SAAS");
        assertThat(notes.get(1)).contains("New York").contains("100%").contains("Delaware");
        assertThat(String.join(" ", notes)).doesNotContain("{");

        doc.setNoticePeriodDays(35);
        doc.setGoverningLawJurisdiction("delaware");
        assertThat(FirmNormsService.deviations(doc, norms, CONFIG.getNormRules(), CONFIG.getNormsMinSupport())).isEmpty();
    }

    @Test
    void firmNormDraftingDefaultsFillOnlyEmptyDealSpecProperties() {
        var docs = org.mockito.Mockito.mock(com.legalpartner.repository.DocumentMetadataRepository.class);
        List<DocumentMetadata> signed = new ArrayList<>();
        for (int i = 0; i < CONFIG.getNormsMinSupport(); i++) {
            DocumentMetadata d = new DocumentMetadata();
            d.setNoticePeriodDays(45);
            signed.add(d);
        }
        org.mockito.Mockito.when(docs.findByDocumentTypeAndContractStatusIn(
                org.mockito.ArgumentMatchers.eq(com.legalpartner.model.enums.DocumentType.MSA),
                org.mockito.ArgumentMatchers.any())).thenReturn(signed);
        var norms = new FirmNormsService(docs, CONFIG);

        var empty = new com.legalpartner.model.dto.DealSpec();
        assertThat(norms.applyDraftingDefaults(empty, com.legalpartner.model.enums.DocumentType.MSA))
                .containsEntry("legal.noticeDays", 45);
        assertThat(empty.getLegal().getNoticeDays()).isEqualTo(45);

        var given = new com.legalpartner.model.dto.DealSpec();
        given.setLegal(new com.legalpartner.model.dto.DealSpec.LegalTerms());
        given.getLegal().setNoticeDays(10);
        assertThat(norms.applyDraftingDefaults(given, com.legalpartner.model.enums.DocumentType.MSA)).isEmpty();
        assertThat(given.getLegal().getNoticeDays()).isEqualTo(10);
    }

    private static FirmClause clause(String position) {
        return FirmClause.builder().position(position).approvedAt(Instant.now()).build();
    }
}
