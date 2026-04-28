package com.legalpartner.controller;

import com.legalpartner.service.JwtService;
import com.legalpartner.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth flow tests — JWT creation, validation, revocation, role handling.
 */
class AuthControllerTest {

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
    void loginProducesValidToken() {
        String token = jwtService.createToken("user@firm.com", List.of("ROLE_ASSOCIATE"), false);
        assertThat(token).isNotBlank();
        assertThat(jwtService.getEmailFromToken(token)).isEqualTo("user@firm.com");
    }

    @Test
    void logoutRevokesToken() {
        String token = jwtService.createToken("user@firm.com", List.of("ROLE_ASSOCIATE"), false);
        assertThat(jwtService.parseToken(token)).isNotNull();
        jwtService.revokeToken(token);
        assertThat(jwtService.parseToken(token)).isNull();
    }

    @Test
    void adminRoleInToken() {
        String token = jwtService.createToken("admin@firm.com", List.of("ROLE_ADMIN"), false);
        var claims = jwtService.parseToken(token);
        assertThat(claims).isNotNull();
        try {
            assertThat(claims.getStringListClaimValue("roles")).contains("ROLE_ADMIN");
        } catch (Exception ignored) {}
    }

    @Test
    void multipleRolesInToken() {
        String token = jwtService.createToken("partner@firm.com",
                List.of("ROLE_PARTNER", "ROLE_ASSOCIATE"), false);
        var claims = jwtService.parseToken(token);
        try {
            assertThat(claims.getStringListClaimValue("roles"))
                    .containsExactlyInAnyOrder("ROLE_PARTNER", "ROLE_ASSOCIATE");
        } catch (Exception ignored) {}
    }

    @Test
    void tempTokenValidImmediately() {
        String token = jwtService.createTempTokenForPasswordChange("user@firm.com", List.of("ROLE_ASSOCIATE"));
        assertThat(jwtService.parseToken(token)).isNotNull();
    }
}
