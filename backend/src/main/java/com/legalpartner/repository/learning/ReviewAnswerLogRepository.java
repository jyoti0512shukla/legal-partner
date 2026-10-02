package com.legalpartner.repository.learning;

import com.legalpartner.model.entity.learning.ReviewAnswerLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewAnswerLogRepository extends JpaRepository<ReviewAnswerLog, UUID> {
    boolean existsByDocumentIdAndQuestionId(UUID documentId, String questionId);

    List<ReviewAnswerLog> findByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}
