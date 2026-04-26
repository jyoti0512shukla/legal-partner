package com.legalpartner.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that default/insecure secrets are not used in production.
 * Fails startup if any known-insecure defaults are detected.
 * Only active when spring.profiles.active != "dev" and != "test".
 */
@Component
@Profile("!dev & !test")
@Slf4j
public class ProductionSecurityValidator implements ApplicationRunner {

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${jasypt.encryptor.password:}")
    private String encryptionKey;

    @Value("${legalpartner.jwt.secret:}")
    private String jwtSecret;

    private static final List<String> INSECURE_DEFAULTS = List.of(
            "localdev123",
            "demo-encryption-key-change-in-prod",
            "change-me-in-production-min-256-bits-required-for-hs256",
            "change-in-prod",
            "password",
            "secret",
            "changeme"
    );

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = new ArrayList<>();

        if (isInsecure(dbPassword)) {
            violations.add("DB_PASSWORD uses an insecure default (" + dbPassword + ")");
        }
        if (isInsecure(encryptionKey) || encryptionKey.isBlank()) {
            violations.add("ENCRYPTION_KEY (jasypt.encryptor.password) is blank or uses an insecure default");
        }
        if (isInsecure(jwtSecret)) {
            violations.add("JWT_SECRET uses an insecure default");
        }
        if (!jwtSecret.isBlank() && jwtSecret.getBytes().length < 32) {
            violations.add("JWT_SECRET is too short (" + jwtSecret.getBytes().length + " bytes, minimum 32 for HS256)");
        }

        if (!violations.isEmpty()) {
            log.error("╔══════════════════════════════════════════════════════════╗");
            log.error("║  SECURITY: Insecure default secrets detected!           ║");
            log.error("╠══════════════════════════════════════════════════════════╣");
            for (String v : violations) {
                log.error("║  • {}", v);
            }
            log.error("╠══════════════════════════════════════════════════════════╣");
            log.error("║  Set secure values in .env or environment variables.    ║");
            log.error("║  To bypass (dev only): --spring.profiles.active=dev     ║");
            log.error("╚══════════════════════════════════════════════════════════╝");
            throw new SecurityException("Refusing to start with insecure default secrets. See logs above.");
        }

        log.info("Security validation passed — no insecure defaults detected.");
    }

    private boolean isInsecure(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase().trim();
        return INSECURE_DEFAULTS.stream().anyMatch(d -> lower.equals(d.toLowerCase()));
    }
}
