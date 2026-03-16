package com.legalpartner.service;

import com.legalpartner.config.JwtProperties;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.HmacKey;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    public String createToken(String email, List<String> roles, boolean rememberMe) {
        int expirationMinutes = rememberMe ? properties.getExpirationMinutesRememberMe() : properties.getExpirationMinutes();
        return createToken(email, roles, expirationMinutes);
    }

    public String createToken(String email, List<String> roles, int expirationMinutes) {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(properties.getIssuer());
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(expirationMinutes);
        claims.setSubject(email);
        claims.setStringListClaim("roles", roles);

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.HMAC_SHA256);
        jws.setKey(new HmacKey(properties.getSecret().getBytes(StandardCharsets.UTF_8)));

        try {
            return jws.getCompactSerialization();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create JWT", e);
        }
    }

    public String createTempTokenForPasswordChange(String email, List<String> roles) {
        return createToken(email, roles, 5); // 5 minutes for password change
    }

    public JwtClaims parseToken(String token) {
        try {
            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setExpectedIssuer(properties.getIssuer())
                    .setVerificationKey(new HmacKey(properties.getSecret().getBytes(StandardCharsets.UTF_8)))
                    .build();
            return consumer.processToClaims(token);
        } catch (Exception e) {
            return null;
        }
    }

    public String getEmailFromToken(String token) {
        JwtClaims claims = parseToken(token);
        if (claims == null) return null;
        try {
            return claims.getSubject();
        } catch (MalformedClaimException e) {
            return null;
        }
    }
}
