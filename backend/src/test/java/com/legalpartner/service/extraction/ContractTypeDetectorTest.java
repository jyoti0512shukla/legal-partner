package com.legalpartner.service.extraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ContractTypeDetectorTest {

    private final ContractTypeDetector detector = new ContractTypeDetector();

    @Test
    void detectsNDA() {
        var result = detector.detect("This Non-Disclosure Agreement is entered into between the parties for the purpose of protecting confidential information shared between them.");
        assertThat(result.contractType()).isEqualTo("NDA");
        assertThat(result.confidence()).isGreaterThan(0.2);
        assertThat(result.signals()).contains("non-disclosure agreement");
    }

    @Test
    void detectsSaaS() {
        var result = detector.detect("This Software as a Service Agreement governs the subscription to the cloud platform. Uptime SLA of 99.9%. Service level credits apply.");
        assertThat(result.contractType()).isEqualTo("SAAS");
        assertThat(result.confidence()).isGreaterThan(0.3);
    }

    @Test
    void detectsSoftwareLicense() {
        var result = detector.detect("This Software License Agreement grants a perpetual license to use the software. The license fee is $100,000 per year.");
        assertThat(result.contractType()).isEqualTo("SOFTWARE_LICENSE");
    }

    @Test
    void detectsEmployment() {
        var result = detector.detect("This Employment Agreement is between the Employer and Employee. Base salary of $150,000. Probation period of 6 months.");
        assertThat(result.contractType()).isEqualTo("EMPLOYMENT");
    }

    @Test
    void detectsMSA() {
        var result = detector.detect("This Master Services Agreement governs all Statements of Work entered into between the parties for professional services.");
        assertThat(result.contractType()).isEqualTo("MSA");
    }

    @Test
    void unknownTextReturnsDefault() {
        var result = detector.detect("Hello world this is a random document with no legal terms.");
        assertThat(result.contractType()).isEqualTo("_default");
        assertThat(result.confidence()).isLessThanOrEqualTo(0.15);
    }

    @Test
    void emptyTextHandled() {
        var result = detector.detect("");
        assertThat(result.contractType()).isEqualTo("_default");
    }

    @Test
    void strongKeywordsScoreHigherThanWeak() {
        // "software" alone (weak) should give lower confidence than "software license agreement" (strong)
        var weakResult = detector.detect("This agreement involves software and support services.");
        var strongResult = detector.detect("This Software License Agreement grants rights to use the software.");
        assertThat(strongResult.confidence()).isGreaterThanOrEqualTo(weakResult.confidence());
    }

    @Test
    void signalsListPopulated() {
        var result = detector.detect("This Non-Disclosure Agreement protects confidential information. Receiving party obligations apply.");
        assertThat(result.signals()).isNotEmpty();
        assertThat(result.signals()).anyMatch(s -> s.contains("non-disclosure") || s.contains("confidential") || s.contains("receiving party"));
    }
}
