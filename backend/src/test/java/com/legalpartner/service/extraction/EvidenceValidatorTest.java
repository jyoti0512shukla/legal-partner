package com.legalpartner.service.extraction;

import com.legalpartner.model.dto.extraction.EvidenceSpan;
import com.legalpartner.model.dto.extraction.ExtractionEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceValidatorTest {

    private final EvidenceValidator validator = new EvidenceValidator();

    private static final String SAMPLE_CONTRACT = """
            ARTICLE 6 — LIABILITY AND INDEMNITY

            Neither Party shall be liable to the other Party for any indirect, incidental,
            special or consequential damages arising out of or in connection with this Agreement,
            whether based on breach of contract, tort (including negligence) or any other cause.

            The aggregate liability of either Party shall not exceed five hundred thousand dollars ($500,000).

            ARTICLE 7 — TERMINATION

            This Agreement may be terminated by either Party upon giving written notice to the other
            Party in the event of a material breach, which remains uncured for sixty (60) days.
            """;

    @Test
    void exactEvidenceMatchGetsHighConfidence() {
        var entry = entryWithEvidence("liability_cap", "$500,000",
                "aggregate liability of either Party shall not exceed five hundred thousand dollars");
        var results = validator.validate(List.of(entry), SAMPLE_CONTRACT);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).extractionConfidence()).isEqualTo("HIGH");
        assertThat(results.get(0).evidence().get(0).charStart()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void normalizedMatchHandlesWhitespace() {
        // Smart quotes and extra whitespace in evidence
        var entry = entryWithEvidence("termination_clause", "60 days",
                "material  breach,  which  remains  uncured  for  sixty");
        var results = validator.validate(List.of(entry), SAMPLE_CONTRACT);
        assertThat(results).hasSize(1);
        // Should still find it via normalized matching
        assertThat(results.get(0).evidence().get(0).charStart()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void noEvidenceGetsLowConfidence() {
        var entry = entryWithEvidence("warranty_period", "12 months",
                "warranty of merchantability for 12 months");
        var results = validator.validate(List.of(entry), SAMPLE_CONTRACT);
        assertThat(results).hasSize(1);
        // Evidence text not in contract → LOW
        assertThat(results.get(0).extractionConfidence()).isIn("LOW", "MEDIUM");
    }

    @Test
    void gapEntriesPassThrough() {
        var entry = new ExtractionEntry("sla", null, null, "OPERATIONAL",
                List.of(), "LOW", "UNCHECKED", "HIGH", "LOW", "NOT_MENTIONED", false, null);
        var results = validator.validate(List.of(entry), SAMPLE_CONTRACT);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).reasonCode()).isEqualTo("NOT_MENTIONED");
    }

    @Test
    void dateFieldTypeCheck() {
        var entry = entryWithEvidence("effective_date", "2026-01-15", "January 15, 2026");
        var results = validator.validate(List.of(entry), "Effective as of January 15, 2026.");
        assertThat(results.get(0).extractionConfidence()).isEqualTo("HIGH");
    }

    @Test
    void amountFieldTypeCheck() {
        var entry = entryWithEvidence("contract_value", "$120,000/year", "$120,000");
        var results = validator.validate(List.of(entry), "Annual fee of $120,000 payable quarterly.");
        assertThat(results.get(0).extractionConfidence()).isEqualTo("HIGH");
    }

    @Test
    void normalizeForMatchingHandlesSmartQuotes() {
        String normalized = EvidenceValidator.normalizeForMatching("Party \u201CA\u201D shall not\u2014");
        assertThat(normalized).isEqualTo("party \"a\" shall not-");
    }

    @Test
    void emptyListHandled() {
        var results = validator.validate(List.of(), SAMPLE_CONTRACT);
        assertThat(results).isEmpty();
    }

    private ExtractionEntry entryWithEvidence(String field, String value, String evidenceText) {
        return new ExtractionEntry(field, field, value, "LEGAL",
                List.of(new EvidenceSpan(evidenceText, -1, -1)),
                null, null, "HIGH", null, null, false, null);
    }
}
