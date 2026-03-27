package com.legalpartner.repository;
import com.legalpartner.model.entity.ReviewStage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ReviewStageRepository extends JpaRepository<ReviewStage, UUID> {
    List<ReviewStage> findByPipelineIdOrderByStageOrderAsc(UUID pipelineId);
}
