package com.legalpartner.repository;

import com.legalpartner.model.entity.WorkflowRun;
import com.legalpartner.model.enums.WorkflowStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {
    Page<WorkflowRun> findByUsernameOrderByStartedAtDesc(String username, Pageable pageable);
    List<WorkflowRun> findByUsernameAndStatusInOrderByStartedAtDesc(String username, List<WorkflowStatus> statuses);
    long countByUsernameAndStatus(String username, WorkflowStatus status);
}
