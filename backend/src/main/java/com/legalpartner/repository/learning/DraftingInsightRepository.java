package com.legalpartner.repository.learning;

import com.legalpartner.model.entity.learning.DraftingInsight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DraftingInsightRepository extends JpaRepository<DraftingInsight, UUID> {
    List<DraftingInsight> findByStatusOrderByUpdatedAtDesc(String status);

    List<DraftingInsight> findByClauseKey(String clauseKey);

    List<DraftingInsight> findByClauseKeyAndStatus(String clauseKey, String status);

    long countByStatus(String status);
}
