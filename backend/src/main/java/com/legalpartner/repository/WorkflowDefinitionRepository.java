package com.legalpartner.repository;

import com.legalpartner.model.entity.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {
    List<WorkflowDefinition> findAllByOrderByCreatedAtAsc();
    boolean existsByNameAndCreatedBy(String name, String createdBy);
}
