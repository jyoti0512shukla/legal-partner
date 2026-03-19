package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contract_analysis")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContractAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID documentId;

    /** "RISK" or "CHECKLIST" */
    @Column(nullable = false, length = 20)
    private String analysisType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String resultJson;

    @Column(nullable = false)
    @Builder.Default
    private Instant analyzedAt = Instant.now();

    @Column(nullable = false, length = 100)
    private String analyzedBy;
}
