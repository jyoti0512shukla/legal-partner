package com.legalpartner.repository;

import com.legalpartner.model.entity.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    /** Returns predefined workflows + team workflows + user's own custom workflows */
    @Query("SELECT d FROM WorkflowDefinition d WHERE d.predefined = true OR d.team = true OR d.createdBy = :username ORDER BY d.createdAt ASC")
    List<WorkflowDefinition> findVisibleToUser(@Param("username") String username);

    /** Used during seeding to avoid duplicate predefined workflows */
    boolean existsByNameAndCreatedByIsNull(String name);

    /** Returns all workflows configured to auto-run on document upload */
    List<WorkflowDefinition> findByAutoTriggerTrue();
}
