package com.legalpartner.repository.learning;

import com.legalpartner.model.entity.learning.FirmClause;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FirmClauseRepository extends JpaRepository<FirmClause, UUID> {
    List<FirmClause> findByStatusOrderByUpdatedAtDesc(String status);

    List<FirmClause> findByClauseKeyAndContractTypeAndStatus(String clauseKey, String contractType, String status);

    Optional<FirmClause> findFirstByFingerprint(String fingerprint);

    long countByStatus(String status);
}
