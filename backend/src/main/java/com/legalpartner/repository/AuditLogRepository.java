package com.legalpartner.repository;

import com.legalpartner.model.entity.AuditLog;
import com.legalpartner.model.enums.AuditActionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query(value = """
            SELECT * FROM audit_logs a WHERE
            (:username IS NULL OR a.username = :username) AND
            (:userRole IS NULL OR a.user_role = :userRole) AND
            (:action IS NULL OR a.action = :action) AND
            (CAST(:from AS timestamptz) IS NULL OR a.timestamp >= CAST(:from AS timestamptz)) AND
            (CAST(:to AS timestamptz) IS NULL OR a.timestamp <= CAST(:to AS timestamptz)) AND
            (CAST(:documentId AS uuid) IS NULL OR a.document_id = CAST(:documentId AS uuid))
            ORDER BY a.timestamp DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM audit_logs a WHERE
            (:username IS NULL OR a.username = :username) AND
            (:userRole IS NULL OR a.user_role = :userRole) AND
            (:action IS NULL OR a.action = :action) AND
            (CAST(:from AS timestamptz) IS NULL OR a.timestamp >= CAST(:from AS timestamptz)) AND
            (CAST(:to AS timestamptz) IS NULL OR a.timestamp <= CAST(:to AS timestamptz)) AND
            (CAST(:documentId AS uuid) IS NULL OR a.document_id = CAST(:documentId AS uuid))
            """,
            nativeQuery = true)
    Page<AuditLog> findFiltered(
            @Param("username") String username,
            @Param("userRole") String userRole,
            @Param("action") String action,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("documentId") String documentId,
            Pageable pageable
    );

    @Query(value = """
            SELECT * FROM audit_logs a WHERE
            (:username IS NULL OR a.username = :username) AND
            (:userRole IS NULL OR a.user_role = :userRole) AND
            (:action IS NULL OR a.action = :action) AND
            (CAST(:from AS timestamptz) IS NULL OR a.timestamp >= CAST(:from AS timestamptz)) AND
            (CAST(:to AS timestamptz) IS NULL OR a.timestamp <= CAST(:to AS timestamptz)) AND
            (CAST(:documentId AS uuid) IS NULL OR a.document_id = CAST(:documentId AS uuid))
            ORDER BY a.timestamp DESC
            """,
            nativeQuery = true)
    List<AuditLog> findFilteredAll(
            @Param("username") String username,
            @Param("userRole") String userRole,
            @Param("action") String action,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("documentId") String documentId
    );

    @Query("SELECT DISTINCT a.username FROM AuditLog a WHERE (:from IS NULL OR a.timestamp >= :from) AND (:to IS NULL OR a.timestamp <= :to) ORDER BY a.username")
    List<String> findDistinctUsernames(@Param("from") Instant from, @Param("to") Instant to);

    long countByAction(AuditActionType action);

    @Query("SELECT a.username, COUNT(a) FROM AuditLog a WHERE a.timestamp >= :from AND a.timestamp <= :to GROUP BY a.username")
    List<Object[]> countByUserBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT CAST(a.timestamp AS date), COUNT(a) FROM AuditLog a WHERE a.timestamp >= :from AND a.timestamp <= :to GROUP BY CAST(a.timestamp AS date)")
    List<Object[]> countByDayBetween(@Param("from") Instant from, @Param("to") Instant to);

    List<AuditLog> findByTimestampBetweenOrderByTimestampDesc(Instant from, Instant to);

    List<AuditLog> findTop10ByOrderByTimestampDesc();
}
