package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "query_feedback")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QueryFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 255)
    private String conversationId;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String queryText;

    @Column(columnDefinition = "TEXT")
    private String answerText;

    private Integer rating;

    private Boolean isCorrect;

    @Column(columnDefinition = "TEXT")
    private String correctedAnswer;

    @Column(columnDefinition = "TEXT")
    private String feedbackNote;

    private UUID matterId;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
