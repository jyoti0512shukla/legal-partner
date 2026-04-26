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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JwtService {

    private final JwtProperties properties;

    /** In-memory blacklist: JTI → expiry timestamp. Entries auto-cleaned on check. */
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();

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
        claims.setJwtId(UUID.randomUUID().toString());
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
        return createToken(email, roles, 5);
    }

    public JwtClaims parseToken(String token) {
        try {
            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setExpectedIssuer(properties.getIssuer())
                    .setVerificationKey(new HmacKey(properties.getSecret().getBytes(StandardCharsets.UTF_8)))
                    .build();
            JwtClaims claims = consumer.processToClaims(token);

            // Check blacklist
            String jti = claims.getJwtId();
            if (jti != null && blacklist.containsKey(jti)) {
                return null; // Token has been revoked
            }

            return claims;
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

    /** Revoke a token by adding its JTI to the blacklist */
    public void revokeToken(String token) {
        try {
            // Parse without blacklist check to get the JTI
            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setExpectedIssuer(properties.getIssuer())
                    .setVerificationKey(new HmacKey(properties.getSecret().getBytes(StandardCharsets.UTF_8)))
                    .build();
            JwtClaims claims = consumer.processToClaims(token);
            String jti = claims.getJwtId();
            if (jti != null) {
                long expiryMs = claims.getExpirationTime().getValueInMillis();
                blacklist.put(jti, expiryMs);
                // Cleanup expired entries (lazy, on each revocation)
                long now = System.currentTimeMillis();
                blacklist.entrySet().removeIf(e -> e.getValue() < now);
            }
        } catch (Exception ignored) {
            // Token may already be invalid — that's fine
        }
    }
}
