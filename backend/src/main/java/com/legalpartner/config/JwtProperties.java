package com.legalpartner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "legalpartner.jwt")
public class JwtProperties {

    private String secret = "change-me-in-production-min-256-bits-required-for-hs256";
    private String issuer = "legal-partner";
    private int expirationMinutes = 60;
    private int expirationMinutesRememberMe = 10080; // 7 days

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public int getExpirationMinutes() {
        return expirationMinutes;
    }

    public void setExpirationMinutes(int expirationMinutes) {
        this.expirationMinutes = expirationMinutes;
    }

    public int getExpirationMinutesRememberMe() {
        return expirationMinutesRememberMe;
    }

    public void setExpirationMinutesRememberMe(int expirationMinutesRememberMe) {
        this.expirationMinutesRememberMe = expirationMinutesRememberMe;
    }
}
