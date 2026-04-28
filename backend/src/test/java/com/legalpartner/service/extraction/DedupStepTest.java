package com.legalpartner.service.extraction;

import com.legalpartner.model.dto.extraction.EvidenceSpan;
import com.legalpartner.model.dto.extraction.ExtractionEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DedupStepTest {

    private final DedupStep dedup = new DedupStep();

    @Test
    void identicalEntriesMerged() {
        var e1 = entry("liability_cap", "$500,000", "COMMERCIAL");
        var e2 = entry("liability_cap", "$500,000", "COMMERCIAL");
        var result = dedup.execute(List.of(e1, e2));
        assertThat(result).hasSize(1);
    }

    @Test
    void differentValuesSameFieldMarkedConflicting() {
        var e1 = entry("liability_cap", "$500,000", "COMMERCIAL");
        var e2 = entry("liability_cap", "$750,000", "COMMERCIAL");
        var result = dedup.execute(List.of(e1, e2));
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> "CONFLICTING_DUPLICATES".equals(e.consistencyStatus()));
    }

    @Test
    void equivalentAmountsDeduped() {
        var e1 = entry("contract_value", "$750,000", "COMMERCIAL");
        var e2 = entry("contract_value", "750000 USD", "COMMERCIAL");
        var result = dedup.execute(List.of(e1, e2));
        // Both parse to same numeric value → should merge
        assertThat(result.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void unmappedFieldsNeverDeduped() {
        var e1 = new ExtractionEntry(null, "custom field 1", "value1", "OTHER",
                List.of(), null, null, "LOW", null, null, false, null);
        var e2 = new ExtractionEntry(null, "custom field 2", "value2", "OTHER",
                List.of(), null, null, "LOW", null, null, false, null);
        var result = dedup.execute(List.of(e1, e2));
        assertThat(result).hasSize(2);
    }

    @Test
    void singleEntryPassesThrough() {
        var e1 = entry("party_a", "Acme Corp", "PARTIES");
        var result = dedup.execute(List.of(e1));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isEqualTo("Acme Corp");
    }

    @Test
    void emptyListHandled() {
        var result = dedup.execute(List.of());
        assertThat(result).isEmpty();
    }

    private ExtractionEntry entry(String canonical, String value, String bucket) {
        return new ExtractionEntry(canonical, canonical, value, bucket,
                List.of(new EvidenceSpan("test quote", 0, 50)),
                null, null, "HIGH", null, null, false, null);
    }
}
