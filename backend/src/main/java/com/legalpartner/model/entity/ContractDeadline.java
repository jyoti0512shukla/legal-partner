package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "contract_deadlines")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ContractDeadline {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentMetadata document;

    /** EXPIRY, NOTICE, RENEWAL, PAYMENT, CUSTOM */
    @Column(name = "deadline_type", nullable = false, length = 50)
    private String deadlineType;

    @Column(name = "deadline_date", nullable = false)
    private LocalDate deadlineDate;

    @Column(length = 500)
    private String description;

    @Column(name = "is_auto_renewal", nullable = false)
    @Builder.Default
    private boolean autoRenewal = false;

    @Column(name = "renewal_term_months")
    private Integer renewalTermMonths;

    @Column(nullable = false)
    @Builder.Default
    private boolean actioned = false;

    @Column(name = "actioned_by", length = 100)
    private String actionedBy;

    @Column(name = "actioned_at")
    private Instant actionedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
