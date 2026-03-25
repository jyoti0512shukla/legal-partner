package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "playbook_positions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PlaybookPosition {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playbook_id", nullable = false)
    private Playbook playbook;

    @Column(name = "clause_type", nullable = false, length = 50)
    private String clauseType;

    @Column(name = "standard_position", nullable = false, columnDefinition = "TEXT")
    private String standardPosition;

    @Column(name = "minimum_acceptable", columnDefinition = "TEXT")
    private String minimumAcceptable;

    @Column(name = "non_negotiable")
    private boolean nonNegotiable;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
