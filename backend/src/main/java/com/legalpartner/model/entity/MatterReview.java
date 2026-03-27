package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "matter_reviews")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MatterReview {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "matter_id", nullable = false) private Matter matter;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "document_id") private DocumentMetadata document;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pipeline_id", nullable = false) private ReviewPipeline pipeline;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "current_stage_id") private ReviewStage currentStage;
    @Column(nullable = false, length = 20) @Builder.Default private String status = "IN_PROGRESS";
    @Column(name = "started_by", nullable = false) private UUID startedBy;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @PrePersist void onCreate() { startedAt = Instant.now(); }
}
