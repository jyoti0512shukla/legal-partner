package com.legalpartner.service;

import com.legalpartner.config.ContractTypeRegistry;
import com.legalpartner.model.dto.DealSpec;
import com.legalpartner.testsupport.ConfigFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every golden clause, rendered exactly as the drafter renders it (deterministic path in
 * DraftService.doAsyncDraft), must pass the deterministic drafting rules at CRITICAL /
 * BLOCK level for a fully specified deal. A golden clause that fails here would be sent
 * back to the LLM for "repair" on every draft — defeating the point of a golden clause.
 *
 * The semantic (LLM) half of this check lives in GoldenClauseReviewLlmTest.
 */
class GoldenClauseRulesTest {

    private final GoldenClauseLibrary golden = ConfigFixtures.goldenClauses();
    private final ClauseRuleEngine rules = ConfigFixtures.ruleEngine();
    private final ContractTypeRegistry contractTypes = ConfigFixtures.contractTypes();

    /**
     * A fully specified deal of the kind this template is used for: parties named by the
     * template's roles, plus only the commercial terms that contract type actually carries
     * (a license fee on an NDA would trigger rules that can never apply to it).
     */
    static DealSpec fullDeal(ContractTypeRegistry.ContractTypeConfig c) {
        var deal = DealSpec.builder()
                .partyA(DealSpec.PartyInfo.builder().name("Northwind Systems, Inc.").type("Corporation")
                        .state("Delaware").address("100 Main Street, Wilmington, DE 19801").role(c.partyARole()).build())
                .partyB(DealSpec.PartyInfo.builder().name("Contoso Retail LLC").type("LLC")
                        .state("New York").address("200 Park Avenue, New York, NY 10166").role(c.partyBRole()).build())
                .legal(DealSpec.LegalTerms.builder().jurisdiction("State of Delaware")
                        .court("Delaware Court of Chancery").arbitration("AAA Commercial Rules")
                        .liabilityCap("12 months of fees").noticeDays(30).cureDays(30).survivalYears(5).build())
                .build();
        switch (c.reviewType()) {
            case "SOFTWARE_LICENSE", "IP_LICENSE" -> {
                deal.setLicense(DealSpec.LicenseTerms.builder().type("perpetual").users(500).locations(3)
                        .derivativeRights(false).deployment("on-premise").build());
                deal.setFees(DealSpec.FeeTerms.builder().licenseFee(750_000L).maintenanceFee(150_000L)
                        .billingCycle("annually").currency("USD").paymentTerms("Net 30").build());
                deal.setSupport(DealSpec.SupportTerms.builder().coverage("24/7").slaResponseHours(4)
                        .patchFrequency("quarterly").build());
                deal.setSecurity(DealSpec.SecurityTerms.builder().escrow(true).build());
            }
            case "SAAS" -> {
                deal.setLicense(DealSpec.LicenseTerms.builder().users(500).deployment("cloud").build());
                deal.setFees(DealSpec.FeeTerms.builder().subscriptionFee(120_000L).billingCycle("annually")
                        .currency("USD").paymentTerms("Net 30").build());
                deal.setSupport(DealSpec.SupportTerms.builder().coverage("24/7").slaResponseHours(4).uptimeSla(99.9).build());
                deal.setSecurity(DealSpec.SecurityTerms.builder().soc2(true).encryptionAtRest(true)
                        .encryptionInTransit(true).build());
            }
            case "MSA", "SUPPLY" -> {
                deal.setFees(DealSpec.FeeTerms.builder().billingCycle("monthly").currency("USD").paymentTerms("Net 30").build());
                deal.setSupport(DealSpec.SupportTerms.builder().slaResponseHours(8).build());
            }
            case "EMPLOYMENT" -> {
                deal.setCompensation(DealSpec.CompensationTerms.builder().salary(185_000L).payFrequency("monthly")
                        .bonus("up to 20% annual performance bonus").build());
                deal.getLegal().setNoticePeriod("one month");
            }
            default -> { }
        }
        return deal;
    }

    /** Same rendering as DraftService's deterministic-first path. */
    static String render(List<GoldenClauseLibrary.GoldenClause> clauses, DealSpec deal, GoldenClauseLibrary lib) {
        StringBuilder html = new StringBuilder();
        for (var gc : clauses) html.append(GoldenClauseLibrary.toHtml(lib.resolve(gc, deal, null)));
        return html.toString();
    }

    @Test
    void goldenClausesPassCriticalDraftingRules() {
        List<String> violations = new ArrayList<>();
        int rendered = 0;
        for (String templateId : contractTypes.allTemplateIds()) {
            var config = contractTypes.get(templateId);
            DealSpec deal = fullDeal(config);
            for (String clauseKey : config.defaultSections()) {
                var clauses = golden.retrieveAll(clauseKey, templateId, "State of Delaware", null);
                if (clauses.isEmpty()) continue;
                String html = render(clauses, deal, golden);
                if (html.length() <= 100) continue; // drafter falls back to LLM below this size
                rendered++;
                for (var r : rules.validate(html, clauseKey, deal)) {
                    boolean critical = "CRITICAL".equalsIgnoreCase(r.rule().severity()) || r.isBlock();
                    if (!r.passed() && critical) {
                        violations.add(templateId + "/" + clauseKey + " → " + r.message());
                    }
                }
            }
        }
        assertThat(rendered).as("golden clauses rendered").isGreaterThan(20);
        assertThat(violations).as("Golden clauses failing CRITICAL/BLOCK drafting rules").isEmpty();
    }

    @Test
    void goldenClausesLeaveNoUnresolvedPlaceholders() {
        List<String> violations = new ArrayList<>();
        for (String templateId : contractTypes.allTemplateIds()) {
            var config = contractTypes.get(templateId);
            DealSpec deal = fullDeal(config);
            for (String clauseKey : config.defaultSections()) {
                for (var gc : golden.retrieveAll(clauseKey, templateId, "State of Delaware", null)) {
                    String text = golden.resolve(gc, deal, null);
                    if (text.contains("{{") || text.contains("}}")) {
                        violations.add(gc.id() + ": unresolved template syntax");
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
