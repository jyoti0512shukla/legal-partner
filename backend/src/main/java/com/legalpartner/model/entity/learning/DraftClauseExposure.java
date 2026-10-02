package com.legalpartner.model.entity.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** What one drafted article was built from (exposure log). */
@Entity
@Table(name = "draft_clause_exposures")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DraftClauseExposure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "clause_key", nullable = false, length = 64)
    private String clauseKey;

    @Column(name = "article", nullable = false)
    private int article;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "template_id", length = 64)
    private String templateId;

    @Column(name = "contract_type", length = 64)
    private String contractType;

    @Column(name = "provenance", nullable = false, length = 20)
    private String provenance;

    @Column(name = "firm_clause_id")
    private UUID firmClauseId;

    @Column(name = "insight_ids", columnDefinition = "TEXT")
    private String insightIds;

    @Column(name = "generated_text", columnDefinition = "TEXT")
    private String generatedText;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
