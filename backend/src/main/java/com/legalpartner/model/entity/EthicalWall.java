package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ethical_walls")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class EthicalWall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "matter_a_id", nullable = false)
    private UUID matterAId;

    @Column(name = "matter_b_id", nullable = false)
    private UUID matterBId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
