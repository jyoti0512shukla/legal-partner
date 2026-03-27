package com.legalpartner.repository;
import com.legalpartner.model.entity.ReviewAction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ReviewActionRepository extends JpaRepository<ReviewAction, UUID> {
    List<ReviewAction> findByReviewIdOrderByCreatedAtDesc(UUID reviewId);
}
