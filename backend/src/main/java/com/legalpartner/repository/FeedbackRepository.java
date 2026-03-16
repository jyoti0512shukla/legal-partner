package com.legalpartner.repository;

import com.legalpartner.model.entity.QueryFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FeedbackRepository extends JpaRepository<QueryFeedback, UUID> {
    Page<QueryFeedback> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);
    Page<QueryFeedback> findByMatterIdOrderByCreatedAtDesc(UUID matterId, Pageable pageable);

    @Query("SELECT AVG(f.rating) FROM QueryFeedback f WHERE f.rating IS NOT NULL")
    Double averageRating();

    @Query("SELECT COUNT(f) FROM QueryFeedback f WHERE f.isCorrect = false")
    long countIncorrectAnswers();
}
