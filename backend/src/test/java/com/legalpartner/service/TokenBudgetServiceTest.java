package com.legalpartner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetServiceTest {

    private TokenBudgetService service;

    @BeforeEach
    void setUp() {
        service = new TokenBudgetService();
        // Can't set @Value fields directly, but we can test the public methods
        // that don't depend on vLLM (fallback estimation path)
    }

    @Test
    void countTokensFallsBackToEstimation() {
        // Without vLLM configured, should use 3 chars/token estimation
        int tokens = service.countTokens("Hello world this is a test");
        assertThat(tokens).isGreaterThan(0);
        // 26 chars / 3 ≈ 9 tokens
        assertThat(tokens).isBetween(5, 15);
    }

    @Test
    void countTokensNullReturnsZero() {
        assertThat(service.countTokens(null)).isEqualTo(0);
    }

    @Test
    void countTokensEmptyReturnsZero() {
        assertThat(service.countTokens("")).isEqualTo(0);
    }

    @Test
    void truncateToTokenBudgetShortTextUnchanged() {
        String text = "This is a short text.";
        String result = service.truncateToTokenBudget(text, 100);
        assertThat(result).isEqualTo(text);
    }

    @Test
    void fitToTokenBudgetWithHintCentersOnRelevantSection() {
        String text = "ARTICLE 1 — DEFINITIONS\nSome definitions here.\n\n" +
                "ARTICLE 2 — LIABILITY\nThe aggregate liability shall not exceed $500,000.\n\n" +
                "ARTICLE 3 — TERMINATION\nEither party may terminate on 30 days notice.";
        String result = service.fitToTokenBudget(text, 20, "liability");
        // Should center on the liability section
        assertThat(result.toLowerCase()).contains("liability");
    }

    @Test
    void fitToTokenBudgetNoHintTakesPrefixAndSuffix() {
        String text = "A".repeat(10000);
        String result = service.fitToTokenBudget(text, 50, null);
        assertThat(result.length()).isLessThan(text.length());
    }

    @Test
    void countTokensCachedReturnsSameValue() {
        int first = service.countTokensCached("test-key", "Some legal text here");
        int second = service.countTokensCached("test-key", "Some legal text here");
        assertThat(first).isEqualTo(second);
    }
}
