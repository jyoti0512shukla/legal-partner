package com.legalpartner.repository;
import com.legalpartner.model.entity.ReviewPipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ReviewPipelineRepository extends JpaRepository<ReviewPipeline, UUID> {
    List<ReviewPipeline> findAllByOrderByCreatedAtDesc();
}
