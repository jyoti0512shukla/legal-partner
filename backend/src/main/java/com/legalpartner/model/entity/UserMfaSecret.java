package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_mfa_secrets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMfaSecret {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 255)
    private String secret;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
