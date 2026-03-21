package com.legalpartner.repository;

import com.legalpartner.model.entity.IntegrationConnection;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, UUID> {

    Optional<IntegrationConnection> findByUserIdAndProvider(UUID userId, String provider);

    boolean existsByUserIdAndProvider(UUID userId, String provider);

    void deleteByUserIdAndProvider(UUID userId, String provider);
}
