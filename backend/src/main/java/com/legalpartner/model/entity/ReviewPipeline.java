package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity @Table(name = "review_pipelines")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReviewPipeline {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "is_default") private boolean isDefault;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stageOrder ASC")
    @Builder.Default private List<ReviewStage> stages = new ArrayList<>();
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
