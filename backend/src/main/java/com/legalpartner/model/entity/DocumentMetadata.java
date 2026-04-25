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

    /** Origin of the document: USER (uploaded), EDGAR (corpus seed), CLOUD (cloud import), DRAFT_ASYNC (background-generated draft) */
    @Column(length = 20, nullable = false)
    @Builder.Default
    private String source = "USER";

    // ── Async draft progress (populated when source = 'DRAFT_ASYNC') ──
    /** Total number of clauses the section planner chose for this draft. */
    @Column(name = "total_clauses")
    private Integer totalClauses;

    /** Number of clauses fully generated so far. */
    @Column(name = "completed_clauses")
    private Integer completedClauses;

    /** Human-readable label of the clause currently being generated (e.g., "Payment"). */
    @Column(name = "current_clause_label", length = 255)
    private String currentClauseLabel;

    /** Timestamp of the last progress update. Used by the stuck-job sweeper. */
    @Column(name = "last_progress_at")
    private Instant lastProgressAt;

    /** If processingStatus = FAILED, the reason. */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    // ── AI-generated summary (cached) ──
    @Column(name = "summary_text", columnDefinition = "TEXT")
    private String summaryText;

    @Column(name = "summary_generated_at")
    private Instant summaryGeneratedAt;

    // ── Cached AI risk assessment ──
    @Column(name = "risk_assessment_json", columnDefinition = "TEXT")
    private String riskAssessmentJson;

    @Column(name = "risk_assessment_at")
    private Instant riskAssessmentAt;

    // ── Cached AI extraction / checklist ──
    @Column(name = "extraction_json", columnDefinition = "TEXT")
    private String extractionJson;

    @Column(name = "extraction_at")
    private Instant extractionAt;

    // ── Anonymization (client-confidentiality layer) ──
    /** Raw→synthetic map of entities substituted at ingest, JSON-encoded. Encrypted at rest by the service layer. */
    @Column(name = "anonymization_map_json", columnDefinition = "TEXT")
    private String anonymizationMapJson;

    /** True if the text stored for RAG embedding was anonymized at ingest. */
    @Column(name = "is_anonymized", nullable = false)
    @Builder.Default
    private boolean anonymized = false;
}
