package com.legalpartner.model.entity.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A lawyer's correction of one review answer. */
@Entity
@Table(name = "review_disputes")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReviewDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "question_id", nullable = false, length = 100)
    private String questionId;

    @Column(name = "clause_type", length = 64)
    private String clauseType;

    @Column(name = "contract_type", length = 64)
    private String contractType;

    @Column(name = "model_answer", length = 20)
    private String modelAnswer;

    @Column(name = "correct_answer", nullable = false, length = 20)
    private String correctAnswer;

    @Column(name = "quote", columnDefinition = "TEXT")
    private String quote;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "disputed_by", length = 255)
    private String disputedBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
