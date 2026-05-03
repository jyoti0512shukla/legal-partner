package com.legalpartner.repository;

import com.legalpartner.model.entity.TrustedDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, UUID> {
    Optional<TrustedDevice> findByDeviceToken(String deviceToken);
    List<TrustedDevice> findByUserIdOrderByLastUsedAtDesc(UUID userId);

    @Modifying @Transactional
    void deleteByUserId(UUID userId);

    @Modifying @Transactional
    @Query("DELETE FROM TrustedDevice t WHERE t.expiresAt < CURRENT_TIMESTAMP")
    void deleteExpired();
}
