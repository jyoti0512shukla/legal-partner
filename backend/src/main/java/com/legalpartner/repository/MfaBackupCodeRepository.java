package com.legalpartner.repository;

import com.legalpartner.model.entity.MfaBackupCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCode, UUID> {
    List<MfaBackupCode> findByUserIdAndUsedFalse(UUID userId);
    List<MfaBackupCode> findByUserId(UUID userId);

    @Transactional
    void deleteByUserId(UUID userId);
}
