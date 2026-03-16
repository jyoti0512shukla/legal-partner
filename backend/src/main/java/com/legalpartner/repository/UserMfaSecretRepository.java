package com.legalpartner.repository;

import com.legalpartner.model.entity.UserMfaSecret;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserMfaSecretRepository extends JpaRepository<UserMfaSecret, UUID> {

    Optional<UserMfaSecret> findByUserId(UUID userId);
}
