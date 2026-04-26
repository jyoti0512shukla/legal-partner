package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signature_envelopes")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SignatureEnvelope {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "envelope_id", nullable = false, unique = true, length = 100)
    private String envelopeId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "matter_id")
    private UUID matterId;

    @Column(name = "sent_by")
    private UUID sentBy;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "sent";

    /** JSON array: [{"email":"...","name":"...","role":"SIGNER|REVIEWER|CC","routingOrder":1,"status":"sent"}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private String recipients = "[]";

    @Column(name = "email_subject")
    private String emailSubject;

    @Column(name = "sent_at", nullable = false)
    @Builder.Default
    private Instant sentAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "signed_pdf_path")
    private String signedPdfPath;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
