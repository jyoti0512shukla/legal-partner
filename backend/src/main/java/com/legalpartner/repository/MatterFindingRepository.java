package com.legalpartner.repository;

import com.legalpartner.model.entity.MatterFinding;
import com.legalpartner.model.enums.FindingSeverity;
import com.legalpartner.model.enums.FindingStatus;
import com.legalpartner.model.enums.FindingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MatterFindingRepository extends JpaRepository<MatterFinding, UUID> {
    List<MatterFinding> findByMatterIdOrderByCreatedAtDesc(UUID matterId);
    List<MatterFinding> findByMatterIdAndSeverity(UUID matterId, FindingSeverity severity);
    List<MatterFinding> findByMatterIdAndStatus(UUID matterId, FindingStatus status);
    List<MatterFinding> findByDocumentId(UUID documentId);
    long countByMatterIdAndStatus(UUID matterId, FindingStatus status);
    @Query("SELECT f.severity, COUNT(f) FROM MatterFinding f WHERE f.matter.id = :matterId GROUP BY f.severity")
    List<Object[]> countBySeverity(@Param("matterId") UUID matterId);
    void deleteByMatterIdAndFindingTypeIn(UUID matterId, List<FindingType> types);

    // Cross-matter queries for dashboard
    List<MatterFinding> findTop20ByOrderByCreatedAtDesc();
    List<MatterFinding> findByStatusOrderByCreatedAtDesc(FindingStatus status);
    long countBySeverity(FindingSeverity severity);
    long countByStatus(FindingStatus status);

    @Query("SELECT f.severity, COUNT(f) FROM MatterFinding f GROUP BY f.severity")
    List<Object[]> countAllBySeverity();

    @Query("SELECT f.matter.id, f.matter.name, f.severity, COUNT(f) FROM MatterFinding f WHERE f.status = 'NEW' GROUP BY f.matter.id, f.matter.name, f.severity ORDER BY COUNT(f) DESC")
    List<Object[]> countUnreviewedByMatterAndSeverity();
}
