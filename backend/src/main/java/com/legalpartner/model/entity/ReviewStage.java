package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity @Table(name = "review_stages")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReviewStage {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pipeline_id", nullable = false)
    private ReviewPipeline pipeline;
    @Column(name = "stage_order", nullable = false) private int stageOrder;
    @Column(nullable = false) private String name;
    @Column(name = "required_role", length = 50) private String requiredRole;
    @Column(nullable = false, length = 500) @Builder.Default private String actions = "APPROVE,RETURN";
    @Column(name = "auto_notify") @Builder.Default private boolean autoNotify = true;
}
