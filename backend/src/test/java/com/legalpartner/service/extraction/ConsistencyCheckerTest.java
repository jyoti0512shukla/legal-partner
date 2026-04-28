package com.legalpartner.service.extraction;

import com.legalpartner.model.dto.extraction.ExtractionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistencyCheckerTest {

    private ConsistencyChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ConsistencyChecker();
        checker.loadBuckets();
    }

    @Test
    void samePartyAAndBFlagged() {
        var entries = List.of(
                entry("party_a", "Acme Corp"),
                entry("party_b", "Acme Corp")
        );
        var result = checker.check(entries);
        assertThat(result.issues()).isNotEmpty();
        assertThat(result.issues().get(0)).containsIgnoringCase("same entity");
    }

    @Test
    void differentPartiesPass() {
        var entries = List.of(
                entry("party_a", "Acme Corp"),
                entry("party_b", "Beta Inc")
        );
        var result = checker.check(entries);
        assertThat(result.issues().stream()
                .filter(i -> i.contains("same entity"))
                .toList()).isEmpty();
    }

    @Test
    void bucketAssignment() {
        assertThat(checker.getBucket("party_a")).isEqualTo("PARTIES");
        assertThat(checker.getBucket("liability_cap")).isEqualTo("COMMERCIAL");
        assertThat(checker.getBucket("governing_law")).isEqualTo("LEGAL");
        assertThat(checker.getBucket("uptime_sla")).isEqualTo("OPERATIONAL");
        assertThat(checker.getBucket("data_protection")).isEqualTo("REGULATORY");
        assertThat(checker.getBucket("effective_date")).isEqualTo("TERM");
        assertThat(checker.getBucket("unknown_field")).isEqualTo("OTHER");
    }

    @Test
    void criticalFieldsLoaded() {
        assertThat(checker.isCritical("liability_cap")).isTrue();
        assertThat(checker.isCritical("governing_law")).isTrue();
        assertThat(checker.isCritical("party_a")).isFalse();
    }

    @Test
    void emptyListHandled() {
        var result = checker.check(List.of());
        assertThat(result.issues()).isEmpty();
    }

    private ExtractionEntry entry(String field, String value) {
        return new ExtractionEntry(field, field, value, checker.getBucket(field),
                List.of(), null, null, "HIGH", null, null, false, null);
    }
}
