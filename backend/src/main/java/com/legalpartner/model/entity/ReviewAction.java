package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "review_actions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReviewAction {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "review_id", nullable = false) private MatterReview review;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "stage_id", nullable = false) private ReviewStage stage;
    @Column(nullable = false, length = 50) private String action;
    @Column(name = "acted_by", nullable = false) private UUID actedBy;
    @Column(columnDefinition = "TEXT") private String notes;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
