package com.legalpartner.model.entity;

import com.legalpartner.model.enums.DocumentType;
import com.legalpartner.model.enums.ExtractionStatus;
import com.legalpartner.model.enums.PracticeArea;
import com.legalpartner.model.enums.ProcessingStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "document_metadata")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DocumentMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 500)
    private String fileName;

    @Column(length = 100)
    private String contentType;

    @Column(name = "stored_path", length = 500)
    private String storedPath;

    @Column(name = "file_size")
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private PracticeArea practiceArea;

    /** Industry vertical for RAG filtering: FINTECH, PHARMA, IT_SERVICES, MANUFACTURING, GENERAL */
    @Column(length = 50)
    private String industry;

    @Column(length = 200)
    private String clientName;

    @Column(length = 100)
    private String matterId;

    @Column(length = 100)
    private String jurisdiction;

    private Integer year;

    private LocalDate effectiveDate;

    @Column(nullable = false)
    private boolean confidential;

    @Column(nullable = false, length = 100)
    private String uploadedBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant uploadDate = Instant.now();

    @Builder.Default
    private int segmentCount = 0;

    private Long fileSizeBytes;

    @Builder.Default
    private boolean ocrApplied = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ProcessingStatus processingStatus = ProcessingStatus.PENDING;

    // Matter relationship (proper FK, alongside legacy String matterId)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matter_uuid")
    private Matter matter;

    // Structured extraction fields (auto-populated after indexing)
    @Column(name = "party_a", length = 500)
    private String partyA;

    @Column(name = "party_b", length = 500)
    private String partyB;

    private LocalDate expiryDate;

    @Column(length = 200)
    private String contractValue;

    @Column(length = 200)
    private String liabilityCap;

    @Column(length = 255)
    private String governingLawJurisdiction;

    private Integer noticePeriodDays;

    @Column(length = 255)
    private String arbitrationVenue;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    @Builder.Default
    private ExtractionStatus extractionStatus = ExtractionStatus.PENDING;

    /** Origin of the document: USER (uploaded), EDGAR (corpus seed), CLOUD (cloud import) */
    @Column(length = 20, nullable = false)
    @Builder.Default
    private String source = "USER";
}
