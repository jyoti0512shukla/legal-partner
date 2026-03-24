package com.legalpartner.repository;

import com.legalpartner.model.entity.WorkflowRun;
import com.legalpartner.model.enums.WorkflowStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    Page<WorkflowRun> findByUsernameOrderByStartedAtDesc(String username, Pageable pageable);

    Page<WorkflowRun> findByUsernameAndMatterRefIgnoreCaseOrderByStartedAtDesc(String username, String matterRef, Pageable pageable);

    long countByUsernameAndStatus(String username, WorkflowStatus status);

    @Query(value = """
            SELECT d.name, COUNT(r.id) AS total,
                   SUM(CASE WHEN r.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed
            FROM workflow_runs r
            JOIN workflow_definitions d ON r.workflow_definition_id = d.id
            WHERE r.username = :username
            GROUP BY d.name
            ORDER BY total DESC
            """, nativeQuery = true)
    List<Object[]> findWorkflowUsageStats(@Param("username") String username);

    @Query(value = """
            SELECT DATE(started_at) AS run_date, COUNT(*) AS run_count
            FROM workflow_runs
            WHERE username = :username AND started_at >= :since
            GROUP BY DATE(started_at)
            ORDER BY run_date
            """, nativeQuery = true)
    List<Object[]> findDailyRunsSince(@Param("username") String username, @Param("since") Instant since);

    @Query(value = """
            SELECT AVG(EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000)
            FROM workflow_runs
            WHERE username = :username AND status = 'COMPLETED' AND completed_at IS NOT NULL
            """, nativeQuery = true)
    Double findAvgDurationMs(@Param("username") String username);

    Page<WorkflowRun> findByMatterRefIgnoreCaseOrderByStartedAtDesc(String matterRef, Pageable pageable);

    Page<WorkflowRun> findByMatterIdInOrderByStartedAtDesc(List<UUID> matterIds, Pageable pageable);
}
