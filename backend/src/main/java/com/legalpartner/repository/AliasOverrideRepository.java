package com.legalpartner.repository;

import com.legalpartner.model.entity.AliasOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AliasOverrideRepository extends JpaRepository<AliasOverride, UUID> {
    Optional<AliasOverride> findByRawField(String rawField);
}
