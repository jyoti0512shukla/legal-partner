package com.legalpartner.model.entity;

import com.legalpartner.model.enums.FindingSeverity;
import com.legalpartner.model.enums.FindingStatus;
import com.legalpartner.model.enums.FindingType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "matter_findings")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MatterFinding {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matter_id", nullable = false)
    private Matter matter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private DocumentMetadata document;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, length = 50)
    private FindingType findingType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FindingSeverity severity;

    @Column(name = "clause_type", length = 50)
    private String clauseType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "section_ref", columnDefinition = "TEXT")
    private String sectionRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playbook_position_id")
    private PlaybookPosition playbookPosition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_document_id")
    private DocumentMetadata relatedDocument;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private FindingStatus status = FindingStatus.NEW;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}
