package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alias_overrides")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AliasOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "raw_field", nullable = false, unique = true, length = 200)
    private String rawField;

    @Column(name = "canonical_field", nullable = false, length = 100)
    private String canonicalField;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
