package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "team_members", uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "user_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TeamMember {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false) private Team team;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(name = "added_at", nullable = false) private Instant addedAt;
    @PrePersist void onCreate() { addedAt = Instant.now(); }
}
