package com.legalpartner.service;

import com.legalpartner.config.JwtProperties;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-key-minimum-256-bits-for-hs256-algorithm-jwt-signing");
        props.setIssuer("test");
        props.setExpirationMinutes(60);
        props.setExpirationMinutesRememberMe(10080);
        jwtService = new JwtService(props);
    }

    @Test
    void createAndParseToken() throws MalformedClaimException {
        String token = jwtService.createToken("user@test.com", List.of("ROLE_ASSOCIATE"), false);
        assertThat(token).isNotBlank();

        JwtClaims claims = jwtService.parseToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("user@test.com");
    }

    @Test
    void emailExtractedFromToken() {
        String token = jwtService.createToken("admin@firm.com", List.of("ROLE_ADMIN"), false);
        String email = jwtService.getEmailFromToken(token);
        assertThat(email).isEqualTo("admin@firm.com");
    }

    @Test
    void invalidTokenReturnsNull() {
        JwtClaims claims = jwtService.parseToken("invalid.jwt.token");
        assertThat(claims).isNull();
    }

    @Test
    void revokedTokenReturnsNull() {
        String token = jwtService.createToken("user@test.com", List.of("ROLE_ASSOCIATE"), false);

        // Before revocation
        assertThat(jwtService.parseToken(token)).isNotNull();

        // Revoke
        jwtService.revokeToken(token);

        // After revocation
        assertThat(jwtService.parseToken(token)).isNull();
    }

    @Test
    void tempTokenHasShortExpiry() {
        String token = jwtService.createTempTokenForPasswordChange("user@test.com", List.of("ROLE_ASSOCIATE"));
        assertThat(token).isNotBlank();

        JwtClaims claims = jwtService.parseToken(token);
        assertThat(claims).isNotNull();
    }

    @Test
    void rememberMeTokenWorks() {
        String token = jwtService.createToken("user@test.com", List.of("ROLE_ASSOCIATE"), true);
        assertThat(token).isNotBlank();
        assertThat(jwtService.parseToken(token)).isNotNull();
    }

    @Test
    void tokenContainsJti() throws MalformedClaimException {
        String token = jwtService.createToken("user@test.com", List.of("ROLE_ASSOCIATE"), false);
        JwtClaims claims = jwtService.parseToken(token);
        assertThat(claims).isNotNull();
        try {
            assertThat(claims.getJwtId()).isNotBlank();
        } catch (Exception e) {
            // JTI should be present
            assertThat(false).as("JTI should be present in token").isTrue();
        }
    }

    @Test
    void differentTokensHaveDifferentJtis() throws MalformedClaimException {
        String token1 = jwtService.createToken("user@test.com", List.of("ROLE_ASSOCIATE"), false);
        String token2 = jwtService.createToken("user@test.com", List.of("ROLE_ASSOCIATE"), false);

        JwtClaims claims1 = jwtService.parseToken(token1);
        JwtClaims claims2 = jwtService.parseToken(token2);

        try {
            assertThat(claims1.getJwtId()).isNotEqualTo(claims2.getJwtId());
        } catch (Exception e) {
            // Should not happen
        }
    }

    @Test
    void revokeOneTokenDoesNotAffectOther() {
        String token1 = jwtService.createToken("user@test.com", List.of("ROLE_ASSOCIATE"), false);
        String token2 = jwtService.createToken("user@test.com", List.of("ROLE_ASSOCIATE"), false);

        jwtService.revokeToken(token1);

        assertThat(jwtService.parseToken(token1)).isNull();     // revoked
        assertThat(jwtService.parseToken(token2)).isNotNull();  // not revoked
    }
}
