package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_definitions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_predefined", nullable = false)
    @Builder.Default
    private boolean predefined = false;

    @Column(name = "is_team", nullable = false)
    @Builder.Default
    private boolean team = false;

    /** Auto-run this workflow whenever a new document is indexed */
    @Column(nullable = false)
    @Builder.Default
    private boolean autoTrigger = false;

    /** JSON array of WorkflowConnector — fires on workflow completion */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private String connectors = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String steps;   // JSON array of step configs

    @Column(length = 255)
    private String createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @UpdateTimestamp
    private Instant updatedAt;
}
