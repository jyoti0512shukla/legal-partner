package com.legalpartner.repository;

import com.legalpartner.model.entity.CloudStorageConnection;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CloudStorageConnectionRepository extends JpaRepository<CloudStorageConnection, UUID> {

    Optional<CloudStorageConnection> findByUserIdAndProvider(UUID userId, String provider);

    boolean existsByUserIdAndProvider(UUID userId, String provider);

    void deleteByUserIdAndProvider(UUID userId, String provider);
}
