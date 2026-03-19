package com.legalpartner.model.entity;

import com.legalpartner.model.enums.WorkflowStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "workflow_definition_id", nullable = false)
    private WorkflowDefinition definition;

    @Column(nullable = false)
    private UUID documentId;

    @Column(nullable = false, length = 255)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private WorkflowStatus status = WorkflowStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int currentStep = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String results;   // JSON map of stepType -> result

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant completedAt;
}
