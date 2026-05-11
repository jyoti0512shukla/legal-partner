package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_versions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"document_id", "version_number"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentMetadata document;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "stored_path", length = 500)
    private String storedPath;

    @Column(name = "file_size")
    private Long fileSize;

    /** AI_GENERATED, UPLOAD, COUNTERPARTY, EDIT */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String source = "UPLOAD";

    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
