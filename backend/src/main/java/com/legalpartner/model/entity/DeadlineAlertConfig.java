package com.legalpartner.model.entity;

import com.legalpartner.model.enums.NotifyChannel;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deadline_alert_config")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeadlineAlertConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "alert_window_days", nullable = false)
    private int alertWindowDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_channel", nullable = false, length = 20)
    @Builder.Default
    private NotifyChannel notifyChannel = NotifyChannel.EMAIL;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
