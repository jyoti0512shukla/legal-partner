package com.legalpartner.repository;

import com.legalpartner.model.entity.PasswordHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, UUID> {

    List<PasswordHistory> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
