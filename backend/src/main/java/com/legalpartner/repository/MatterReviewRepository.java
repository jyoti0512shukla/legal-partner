package com.legalpartner.repository;
import com.legalpartner.model.entity.MatterReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface MatterReviewRepository extends JpaRepository<MatterReview, UUID> {
    List<MatterReview> findByMatterIdOrderByStartedAtDesc(UUID matterId);
    List<MatterReview> findByStatusOrderByStartedAtDesc(String status);
    @Query("SELECT r FROM MatterReview r WHERE r.status = 'IN_PROGRESS' AND r.currentStage.requiredRole = :role")
    List<MatterReview> findPendingByRole(@Param("role") String role);
    @Query("SELECT r FROM MatterReview r WHERE r.status = 'IN_PROGRESS' AND r.matter.id IN :matterIds")
    List<MatterReview> findInProgressByMatterIds(@Param("matterIds") List<UUID> matterIds);
}
