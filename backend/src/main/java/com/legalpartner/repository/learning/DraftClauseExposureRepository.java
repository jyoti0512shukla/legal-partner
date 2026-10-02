package com.legalpartner.repository.learning;

import com.legalpartner.model.entity.learning.DraftClauseExposure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DraftClauseExposureRepository extends JpaRepository<DraftClauseExposure, UUID> {
    List<DraftClauseExposure> findByDocumentIdOrderByArticle(UUID documentId);

    List<DraftClauseExposure> findByCreatedAtAfter(Instant since);

    void deleteByDocumentId(UUID documentId);
}
