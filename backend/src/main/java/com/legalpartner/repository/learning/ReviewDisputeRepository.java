package com.legalpartner.repository.learning;

import com.legalpartner.model.entity.learning.ReviewDispute;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewDisputeRepository extends JpaRepository<ReviewDispute, UUID> {
    List<ReviewDispute> findByQuestionIdOrderByCreatedAtDesc(String questionId, Pageable page);

    long countByCreatedAtAfter(Instant since);

    List<ReviewDispute> findByDocumentId(UUID documentId);

    Optional<ReviewDispute> findFirstByDocumentIdAndQuestionId(UUID documentId, String questionId);

    void deleteByDocumentId(UUID documentId);
}
