package com.legalpartner.model.entity;

import com.legalpartner.model.enums.AuditActionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Immutable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    @Builder.Default
    private Instant timestamp = Instant.now();

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 50)
    private String userRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditActionType action;

    @Column(length = 500)
    private String endpoint;

    @Column(length = 10)
    private String httpMethod;

    private UUID documentId;

    @Column(columnDefinition = "TEXT")
    private String queryText;

    @Column(columnDefinition = "TEXT")
    private String retrievedDocIds;

    private Long responseTimeMs;

    @Column(length = 50)
    private String ipAddress;

    @Column(nullable = false)
    @Builder.Default
    private boolean success = true;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}
