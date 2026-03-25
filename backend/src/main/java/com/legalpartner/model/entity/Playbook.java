package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "playbooks")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Playbook {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "deal_type", nullable = false, length = 50)
    private String dealType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_default")
    private boolean isDefault;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @OneToMany(mappedBy = "playbook", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PlaybookPosition> positions = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
