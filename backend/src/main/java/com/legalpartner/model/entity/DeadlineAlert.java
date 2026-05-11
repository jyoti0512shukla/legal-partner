package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "deadline_alerts")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeadlineAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deadline_id", nullable = false)
    private ContractDeadline deadline;

    @Column(name = "alert_date", nullable = false)
    private LocalDate alertDate;

    @Column(name = "alert_window_days", nullable = false)
    private int alertWindowDays;

    @Column(nullable = false)
    @Builder.Default
    private boolean sent = false;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
