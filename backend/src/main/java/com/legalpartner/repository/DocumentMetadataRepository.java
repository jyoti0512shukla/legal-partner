package com.legalpartner.repository;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.enums.ContractStatus;
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

    @Query("SELECT DISTINCT d.clientName FROM DocumentMetadata d WHERE d.clientName IS NOT NULL AND d.clientName <> '' ORDER BY d.clientName ASC")
    List<String> findDistinctClientNames();

    @Query(value = "SELECT * FROM document_metadata d WHERE d.source <> 'EDGAR' AND (LOWER(d.client_name) LIKE LOWER(CONCAT('%%', :name, '%%')) OR LOWER(d.party_a) LIKE LOWER(CONCAT('%%', :name, '%%')) OR LOWER(d.party_b) LIKE LOWER(CONCAT('%%', :name, '%%'))) ORDER BY d.upload_date DESC LIMIT 10", nativeQuery = true)
    List<DocumentMetadata> findByClientOrPartyFuzzy(@Param("name") String name);

    // ── Search & Filter ──

    @Query(value = """
            SELECT * FROM document_metadata d WHERE
            d.source <> 'EDGAR' AND
            (:confidentialFilter = false OR d.confidential = false) AND
            (:search IS NULL OR (
                LOWER(d.file_name) LIKE LOWER(CONCAT('%%', :search, '%%')) OR
                LOWER(d.party_a) LIKE LOWER(CONCAT('%%', :search, '%%')) OR
                LOWER(d.party_b) LIKE LOWER(CONCAT('%%', :search, '%%')) OR
                LOWER(d.client_name) LIKE LOWER(CONCAT('%%', :search, '%%'))
            )) AND
            (CAST(:contractStatus AS VARCHAR) IS NULL OR d.contract_status = :contractStatus) AND
            (CAST(:documentType AS VARCHAR) IS NULL OR d.document_type = :documentType) AND
            (CAST(:matterId AS VARCHAR) IS NULL OR CAST(d.matter_uuid AS VARCHAR) = :matterId) AND
            (CAST(:expiryBefore AS DATE) IS NULL OR d.expiry_date <= CAST(:expiryBefore AS DATE)) AND
            (CAST(:expiryAfter AS DATE) IS NULL OR d.expiry_date >= CAST(:expiryAfter AS DATE))
            """,
            countQuery = """
            SELECT COUNT(*) FROM document_metadata d WHERE
            d.source <> 'EDGAR' AND
            (:confidentialFilter = false OR d.confidential = false) AND
            (:search IS NULL OR (
                LOWER(d.file_name) LIKE LOWER(CONCAT('%%', :search, '%%')) OR
                LOWER(d.party_a) LIKE LOWER(CONCAT('%%', :search, '%%')) OR
                LOWER(d.party_b) LIKE LOWER(CONCAT('%%', :search, '%%')) OR
                LOWER(d.client_name) LIKE LOWER(CONCAT('%%', :search, '%%'))
            )) AND
            (CAST(:contractStatus AS VARCHAR) IS NULL OR d.contract_status = :contractStatus) AND
            (CAST(:documentType AS VARCHAR) IS NULL OR d.document_type = :documentType) AND
            (CAST(:matterId AS VARCHAR) IS NULL OR CAST(d.matter_uuid AS VARCHAR) = :matterId) AND
            (CAST(:expiryBefore AS DATE) IS NULL OR d.expiry_date <= CAST(:expiryBefore AS DATE)) AND
            (CAST(:expiryAfter AS DATE) IS NULL OR d.expiry_date >= CAST(:expiryAfter AS DATE))
            """,
            nativeQuery = true)
    Page<DocumentMetadata> searchAndFilter(
            @Param("confidentialFilter") boolean confidentialFilter,
            @Param("search") String search,
            @Param("contractStatus") String contractStatus,
            @Param("documentType") String documentType,
            @Param("matterId") String matterId,
            @Param("expiryBefore") String expiryBefore,
            @Param("expiryAfter") String expiryAfter,
            Pageable pageable);

    // ── Contract Lifecycle queries ──

    List<DocumentMetadata> findByContractStatus(ContractStatus status);

    @Query("SELECT d FROM DocumentMetadata d WHERE d.contractStatus = com.legalpartner.model.enums.ContractStatus.ACTIVE AND d.expiryDate IS NOT NULL AND d.expiryDate <= :cutoff")
    List<DocumentMetadata> findActiveExpiringBefore(@Param("cutoff") java.time.LocalDate cutoff);

    @Query("SELECT d FROM DocumentMetadata d WHERE d.contractStatus = com.legalpartner.model.enums.ContractStatus.EXPIRING AND d.expiryDate IS NOT NULL AND d.expiryDate < :today")
    List<DocumentMetadata> findExpiredContracts(@Param("today") java.time.LocalDate today);

    @Query("SELECT COUNT(d) FROM DocumentMetadata d WHERE d.contractStatus IN (com.legalpartner.model.enums.ContractStatus.ACTIVE, com.legalpartner.model.enums.ContractStatus.EXPIRING)")
    long countActiveContracts();
}
