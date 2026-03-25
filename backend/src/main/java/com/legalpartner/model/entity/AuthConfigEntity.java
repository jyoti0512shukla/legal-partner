package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "auth_config")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AuthConfigEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "invite_expiry_hours") @Builder.Default private int inviteExpiryHours = 72;
    @Column(name = "invite_resend_cooldown_min") @Builder.Default private int inviteResendCooldownMin = 30;
    @Column(name = "password_reset_expiry_hours") @Builder.Default private int passwordResetExpiryHours = 24;
    @Column(name = "max_password_resets_per_hour") @Builder.Default private int maxPasswordResetsPerHour = 3;
    @Column(name = "max_failed_logins") @Builder.Default private int maxFailedLogins = 5;
    @Column(name = "lockout_duration_minutes") @Builder.Default private int lockoutDurationMinutes = 15;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
