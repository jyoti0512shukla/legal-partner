package com.legalpartner.repository.learning;

import com.legalpartner.model.entity.learning.ClauseObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClauseObservationRepository extends JpaRepository<ClauseObservation, UUID> {
    Optional<ClauseObservation> findByDocumentIdAndClauseKey(UUID documentId, String clauseKey);

    List<ClauseObservation> findByClauseKeyAndContractType(String clauseKey, String contractType);

    @org.springframework.data.jpa.repository.Query("select distinct o.clauseKey, o.contractType from ClauseObservation o")
    List<Object[]> findGroups();

    void deleteByDocumentId(UUID documentId);
}
