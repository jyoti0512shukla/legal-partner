package com.legalpartner.repository;

import com.legalpartner.model.entity.ClauseLibraryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClauseLibraryRepository extends JpaRepository<ClauseLibraryEntry, UUID> {

    List<ClauseLibraryEntry> findByClauseTypeOrderByGoldenDescUsageCountDesc(String clauseType);

    /**
     * Find clauses matching the given type, with optional filters.
     * Null filters are treated as "any" (wildcard).
     * Golden clauses come first, then by usage count.
     */
    @Query("""
            SELECT c FROM ClauseLibraryEntry c
            WHERE c.clauseType = :clauseType
              AND (:contractType IS NULL OR c.contractType IS NULL OR c.contractType = :contractType)
              AND (:industry     IS NULL OR c.industry     IS NULL OR c.industry     = :industry)
              AND (:practiceArea IS NULL OR c.practiceArea IS NULL OR c.practiceArea = :practiceArea)
            ORDER BY c.golden DESC, c.usageCount DESC
            """)
    List<ClauseLibraryEntry> findForDraft(
            @Param("clauseType")   String clauseType,
            @Param("contractType") String contractType,
            @Param("industry")     String industry,
            @Param("practiceArea") String practiceArea
    );

    List<ClauseLibraryEntry> findAllByOrderByClauseTypeAscGoldenDescCreatedAtDesc();
}
