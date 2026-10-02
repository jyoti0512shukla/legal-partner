package com.legalpartner.model.entity.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A learned drafting preference — one bullet of the evolving firm playbook. */
@Entity
@Table(name = "drafting_insights")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DraftingInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "clause_key", nullable = false, length = 64)
    private String clauseKey;

    @Column(name = "contract_type", length = 64)
    private String contractType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PROPOSED";

    @Column(name = "evidence_count", nullable = false)
    @Builder.Default
    private int evidenceCount = 0;

    @Column(name = "evidence_docs", columnDefinition = "TEXT")
    private String evidenceDocs;

    @Column(name = "helpful_count", nullable = false)
    @Builder.Default
    private int helpfulCount = 0;

    @Column(name = "harmful_count", nullable = false)
    @Builder.Default
    private int harmfulCount = 0;

    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private String source = "EDIT_REFLECTION";

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
