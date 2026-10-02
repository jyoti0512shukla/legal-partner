package com.legalpartner.model.entity.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Answer / dispute counts per review question and contract type. */
@Entity
@Table(name = "question_calibration")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionCalibration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "question_id", nullable = false, length = 100)
    private String questionId;

    @Column(name = "contract_type", nullable = false, length = 64)
    private String contractType;

    @Column(name = "answered", nullable = false)
    @Builder.Default
    private int answered = 0;

    @Column(name = "disputed", nullable = false)
    @Builder.Default
    private int disputed = 0;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
