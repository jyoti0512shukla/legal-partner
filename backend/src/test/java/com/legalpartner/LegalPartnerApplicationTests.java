package com.legalpartner;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Full application context load test — requires PostgreSQL + Ollama.
 * Disabled for unit test runs. Run as integration test with full infra.
 */
@Disabled("Requires PostgreSQL + Ollama — run integration tests separately")
class LegalPartnerApplicationTests {

    @Test
    void contextLoads() {
    }
}
