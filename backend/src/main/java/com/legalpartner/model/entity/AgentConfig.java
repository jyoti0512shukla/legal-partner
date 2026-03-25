package com.legalpartner.model.entity;

import com.legalpartner.model.enums.NotifyChannel;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "agent_config")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentConfig {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "auto_analyze_on_upload")
    @Builder.Default
    private boolean autoAnalyzeOnUpload = true;

    @Column(name = "cross_reference_docs")
    @Builder.Default
    private boolean crossReferenceDocs = true;

    @Column(name = "check_playbook")
    @Builder.Default
    private boolean checkPlaybook = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_high", length = 50)
    @Builder.Default
    private NotifyChannel notifyHigh = NotifyChannel.IN_APP;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_medium", length = 50)
    @Builder.Default
    private NotifyChannel notifyMedium = NotifyChannel.IN_APP;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_low", length = 50)
    @Builder.Default
    private NotifyChannel notifyLow = NotifyChannel.NONE;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
