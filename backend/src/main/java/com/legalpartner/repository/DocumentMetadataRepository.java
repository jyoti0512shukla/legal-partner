package com.legalpartner.repository;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.enums.ProcessingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, UUID> {

    Page<DocumentMetadata> findByConfidentialFalse(Pageable pageable);

    List<DocumentMetadata> findByProcessingStatus(ProcessingStatus status);

    @Query("SELECT SUM(d.segmentCount) FROM DocumentMetadata d")
    Long sumSegmentCount();

    @Query("SELECT d.jurisdiction, COUNT(d) FROM DocumentMetadata d WHERE d.jurisdiction IS NOT NULL GROUP BY d.jurisdiction")
    List<Object[]> countByJurisdiction();

    @Query("SELECT d.practiceArea, COUNT(d) FROM DocumentMetadata d WHERE d.practiceArea IS NOT NULL GROUP BY d.practiceArea")
    List<Object[]> countByPracticeArea();
}
