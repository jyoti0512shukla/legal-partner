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

    @Query("""
            SELECT a FROM AuditLog a WHERE
            (:username IS NULL OR a.username = :username) AND
            (:action IS NULL OR a.action = :action) AND
            (:from IS NULL OR a.timestamp >= :from) AND
            (:to IS NULL OR a.timestamp <= :to) AND
            (:documentId IS NULL OR a.documentId = :documentId)
            ORDER BY a.timestamp DESC
            """)
    Page<AuditLog> findFiltered(
            @Param("username") String username,
            @Param("action") AuditActionType action,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("documentId") UUID documentId,
            Pageable pageable
    );

    long countByAction(AuditActionType action);

    @Query("SELECT a.username, COUNT(a) FROM AuditLog a WHERE a.timestamp >= :from AND a.timestamp <= :to GROUP BY a.username")
    List<Object[]> countByUserBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT CAST(a.timestamp AS date), COUNT(a) FROM AuditLog a WHERE a.timestamp >= :from AND a.timestamp <= :to GROUP BY CAST(a.timestamp AS date)")
    List<Object[]> countByDayBetween(@Param("from") Instant from, @Param("to") Instant to);

    List<AuditLog> findByTimestampBetweenOrderByTimestampDesc(Instant from, Instant to);

    List<AuditLog> findTop10ByOrderByTimestampDesc();
}
