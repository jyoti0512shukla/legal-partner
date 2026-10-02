package com.legalpartner.model.entity.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A segmented clause from a firm document, with its MinHash signature. */
@Entity
@Table(name = "clause_observations")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ClauseObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "clause_key", nullable = false, length = 64)
    private String clauseKey;

    @Column(name = "contract_type", nullable = false, length = 64)
    private String contractType;

    @Column(name = "text_enc", nullable = false, columnDefinition = "TEXT")
    private String textEnc;

    @Column(name = "text_sha256", nullable = false, length = 64)
    private String textSha256;

    @Column(name = "minhash", nullable = false, columnDefinition = "TEXT")
    private String minhash;

    @Column(name = "executed", nullable = false)
    @Builder.Default
    private boolean executed = false;

    @Column(name = "observed_at", nullable = false)
    @Builder.Default
    private Instant observedAt = Instant.now();
}
