package com.legalpartner.repository.learning;

import com.legalpartner.model.entity.learning.ClauseEdit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClauseEditRepository extends JpaRepository<ClauseEdit, UUID> {
    Optional<ClauseEdit> findByDocumentIdAndClauseKey(UUID documentId, String clauseKey);

    List<ClauseEdit> findByCapturedAtAfter(Instant since);

    List<ClauseEdit> findByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}
