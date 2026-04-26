package com.legalpartner.repository;

import com.legalpartner.model.entity.IntegrationConnection;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, UUID> {

    Optional<IntegrationConnection> findByUserIdAndProvider(UUID userId, String provider);

    /** Find org-level connection for a provider (any user who connected it as ORGANIZATION scope) */
    Optional<IntegrationConnection> findFirstByProviderAndScope(String provider, String scope);

    boolean existsByUserIdAndProvider(UUID userId, String provider);

    void deleteByUserIdAndProvider(UUID userId, String provider);
}
