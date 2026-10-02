package com.legalpartner.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LegalSystemConfigTest {

    /** Every marker the prompt library uses. */
    static final List<String> MARKERS = List.of(
            "COUNTRY", "LEGAL_ANALYST_EXPERTISE", "LEGAL_DRAFTSMAN", "LEGAL_RISK_ANALYST",
            "LEGAL_RISK_CONSULTANT", "LEGAL_EDITOR", "LEGAL_REVIEWER", "LAW_SYSTEM", "LAW_COMPLIANCE",
            "CONTRACT_ACT_LIABILITY_SECTIONS", "CONTRACT_ACT_REPUDIATION", "CONTRACT_ACT_MISREPRESENTATION",
            "CONTRACT_ACT_LAWFUL_OBJECT", "CONTRACT_ACT", "ARBITRATION_ACT", "ARBITRATION_SEAT", "COURT_SEAT",
            "GOVERNING_LAW_STATEMENT", "DISPUTE_RESOLUTION_CLAUSE", "IP_LAWS", "MORAL_RIGHTS_REF",
            "DATA_PROTECTION_LAWS", "DATA_PROTECTION_LAWS_ABBREV", "TAX_REF", "MSME_REF");

    static final String ALL_MARKERS_PROMPT = String.join("\n", MARKERS.stream().map(m -> m + "=%" + m + "%").toList());

    static LegalSystemConfig config(String system) {
        LegalSystemConfig c = new LegalSystemConfig();
        c.setLegalSystem(system);
        ReflectionTestUtils.invokeMethod(c, "load");
        return c;
    }

    /**
     * Spot checks pinned during the move from Java to jurisdictions.yml (the migration was
     * verified output-identical for 15 jurisdiction strings × 2 server defaults).
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "California, United States | COURT_SEAT      | San Francisco, California",
            "State of Delaware         | COURT_SEAT      | Wilmington, Delaware",
            "United States             | GOVERNING_LAW_STATEMENT | Governing law — laws of the State of the applicable state, United States.",
            "England and Wales         | ARBITRATION_ACT | Arbitration Act 1996",
            "Singapore                 | ARBITRATION_ACT | International Arbitration Act (Cap. 143A) and SIAC Rules",
            "Germany                   | CONTRACT_ACT    | German law (BGB)",
            "France                    | COUNTRY         | France",
            "Victoria, Australia       | GOVERNING_LAW_STATEMENT | Governing law — laws of Victoria, Australia.",
            "India (Maharashtra)       | ARBITRATION_SEAT | Mumbai, Maharashtra, India",
            "Mars                      | COUNTRY         | India"})
    void resolvesExpectedReferences(String jurisdiction, String marker, String expected) {
        assertThat(config("USA").localizeForJurisdiction("%" + marker + "%", jurisdiction)).isEqualTo(expected);
    }

    @Test
    void jurisdictionFamiliesComeFromTheRegistry() {
        LegalSystemConfig c = config("USA");
        assertThat(c.family("Delaware")).contains("us");
        assertThat(c.family("California, United States")).contains("us");
        assertThat(c.family("Texas")).contains("us");
        assertThat(c.family("London, United Kingdom")).contains("uk");
        assertThat(c.family("Mumbai")).contains("india");
        assertThat(c.family("Germany")).isNotEqualTo(c.family("France"));
        assertThat(c.family("Atlantis")).isEmpty();
    }

    @Test
    void noJurisdictionUsesServerDefault() {
        assertThat(config("USA").localizeForJurisdiction("%COUNTRY%", null)).isEqualTo("United States");
        assertThat(config("INDIA").localizeForJurisdiction("%COUNTRY%", null)).isEqualTo("India");
    }

    @Test
    void newSouthWalesIsAustralianNotEnglishLaw() {
        // Deliberate fix: the old code checked "wales" before "australia".
        assertThat(config("USA").resolveId("New South Wales, Australia")).isEqualTo("au");
        assertThat(config("USA").localizeForJurisdiction("%GOVERNING_LAW_STATEMENT%", "New South Wales, Australia"))
                .isEqualTo("Governing law — laws of New South Wales, Australia.");
    }

    @Test
    void everyJurisdictionDefinesEveryMarker() {
        for (var j : config("USA").jurisdictions()) {
            assertThat(j.markers().keySet()).as(j.id()).containsAll(MARKERS);
        }
    }

    @Test
    void serverLevelAccessorsFollowLegalSystem() {
        assertThat(config("USA").country()).isEqualTo("United States");
        assertThat(config("INDIA").country()).isEqualTo("India");
        assertThat(config("INDIA").arbitrationAct()).isEqualTo("Arbitration and Conciliation Act 1996");
        assertThat(config("USA").contractAct()).contains("Uniform Commercial Code");
    }

    @Test
    void unknownMarkersAreLeftAlone() {
        assertThat(config("USA").localizeForJurisdiction("%NOT_A_MARKER% and %COUNTRY%", null))
                .isEqualTo("%NOT_A_MARKER% and United States");
        assertThat(Set.of()).isEmpty();
    }
}
