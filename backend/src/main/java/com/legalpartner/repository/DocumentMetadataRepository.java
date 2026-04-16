package com.legalpartner.repository;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.enums.ExtractionStatus;
import com.legalpartner.model.enums.ProcessingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, UUID> {

    Page<DocumentMetadata> findByConfidentialFalse(Pageable pageable);

    Page<DocumentMetadata> findBySourceNot(String source, Pageable pageable);

    Page<DocumentMetadata> findBySourceNotAndConfidentialFalse(String source, Pageable pageable);

    List<DocumentMetadata> findByProcessingStatus(ProcessingStatus status);

    @Query("SELECT SUM(d.segmentCount) FROM DocumentMetadata d")
    Long sumSegmentCount();

    @Query("SELECT d.jurisdiction, COUNT(d) FROM DocumentMetadata d WHERE d.jurisdiction IS NOT NULL GROUP BY d.jurisdiction")
    List<Object[]> countByJurisdiction();

    @Query("SELECT d.practiceArea, COUNT(d) FROM DocumentMetadata d WHERE d.practiceArea IS NOT NULL GROUP BY d.practiceArea")
    List<Object[]> countByPracticeArea();

    Page<DocumentMetadata> findByMatter_Id(UUID matterUuid, Pageable pageable);

    @Query("SELECT d FROM DocumentMetadata d WHERE d.matter.id = :matterId")
    List<DocumentMetadata> findAllByMatterId(@Param("matterId") UUID matterId);

    Page<DocumentMetadata> findByMatter_IdAndConfidentialFalse(UUID matterUuid, Pageable pageable);

    @Query("SELECT COUNT(d) FROM DocumentMetadata d WHERE d.matter.id = :matterUuid")
    int countByMatterUuid(@Param("matterUuid") UUID matterUuid);

    List<DocumentMetadata> findByExtractionStatus(ExtractionStatus status);

    /** Async drafts for the Recent Drafts strip. Most recent first. */
    List<DocumentMetadata> findTop20ByUploadedByAndSourceOrderByUploadDateDesc(String uploadedBy, String source);

    /** Used by the startup + stuck-job sweepers. */
    List<DocumentMetadata> findBySourceAndProcessingStatus(String source, ProcessingStatus status);

    @Query("SELECT CAST(d.id AS string) FROM DocumentMetadata d WHERE d.matter.id = :matterUuid")
    List<String> findIdStringsByMatterUuid(@Param("matterUuid") UUID matterUuid);
}
