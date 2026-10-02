package com.legalpartner.model.entity.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** AI text vs the lawyer's text for one article of a draft (encrypted). */
@Entity
@Table(name = "clause_edits")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ClauseEdit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "clause_key", nullable = false, length = 64)
    private String clauseKey;

    @Column(name = "article", nullable = false)
    private int article;

    @Column(name = "template_id", length = 64)
    private String templateId;

    @Column(name = "ai_text", columnDefinition = "TEXT")
    private String aiText;

    @Column(name = "final_text", columnDefinition = "TEXT")
    private String finalText;

    @Column(name = "edit_ratio", nullable = false)
    private double editRatio;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "is_final", nullable = false)
    @Builder.Default
    private boolean isFinal = false;

    @Column(name = "reflected", nullable = false)
    @Builder.Default
    private boolean reflected = false;

    @Column(name = "captured_at", nullable = false)
    @Builder.Default
    private Instant capturedAt = Instant.now();
}
