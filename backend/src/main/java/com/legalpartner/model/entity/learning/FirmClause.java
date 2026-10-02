package com.legalpartner.model.entity.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A mined candidate or lawyer-approved firm clause. */
@Entity
@Table(name = "firm_clauses")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class FirmClause {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "clause_key", nullable = false, length = 64)
    private String clauseKey;

    @Column(name = "contract_type", nullable = false, length = 64)
    private String contractType;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "CANDIDATE";

    @Column(name = "position", nullable = false, length = 20)
    @Builder.Default
    private String position = "PRIMARY";

    @Column(name = "text_enc", nullable = false, columnDefinition = "TEXT")
    private String textEnc;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "support_count", nullable = false)
    private int supportCount;

    @Column(name = "executed_count", nullable = false)
    private int executedCount;

    @Column(name = "source_document_ids", columnDefinition = "TEXT")
    private String sourceDocumentIds;

    @Column(name = "times_used", nullable = false)
    @Builder.Default
    private int timesUsed = 0;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
