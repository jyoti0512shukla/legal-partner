package com.legalpartner.model.entity.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One counted review answer per (document, question) — keeps calibration counts honest. */
@Entity
@Table(name = "review_answer_log")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReviewAnswerLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "question_id", nullable = false, length = 100)
    private String questionId;

    @Column(name = "contract_type", length = 64)
    private String contractType;

    @Column(name = "answered_at", nullable = false)
    @Builder.Default
    private Instant answeredAt = Instant.now();
}
