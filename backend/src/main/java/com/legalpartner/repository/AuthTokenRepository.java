package com.legalpartner.repository;

import com.legalpartner.model.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {
    Optional<AuthToken> findByToken(String token);
    List<AuthToken> findByUserIdAndTokenType(UUID userId, String tokenType);
    long countByUserIdAndTokenTypeAndCreatedAtAfter(UUID userId, String tokenType, Instant after);
    Optional<AuthToken> findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(UUID userId, String tokenType);
}
