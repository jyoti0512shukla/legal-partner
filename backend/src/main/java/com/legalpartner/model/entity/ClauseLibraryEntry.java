package com.legalpartner.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clause_library")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClauseLibraryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** One of: DEFINITIONS, SERVICES, PAYMENT, CONFIDENTIALITY, IP_RIGHTS, LIABILITY,
     *  TERMINATION, FORCE_MAJEURE, REPRESENTATIONS_WARRANTIES, DATA_PROTECTION,
     *  GOVERNING_LAW, GENERAL_PROVISIONS */
    @Column(name = "clause_type", nullable = false, length = 50)
    private String clauseType;

    @Column(nullable = false, length = 200)
    private String title;

    /** Full clause text — plain text or numbered sub-clauses */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** NDA, MSA, SOW — null means applies to any contract type */
    @Column(name = "contract_type", length = 50)
    private String contractType;

    /** CORPORATE, IP, TAX etc. — null means any practice area */
    @Column(name = "practice_area", length = 50)
    private String practiceArea;

    /** FINTECH, PHARMA, IT_SERVICES, MANUFACTURING, GENERAL — null means any */
    @Column(length = 50)
    private String industry;

    /** vendor, client, employee, startup — null means any */
    @Column(name = "counterparty_type", length = 50)
    private String counterpartyType;

    /** Maharashtra, Delhi, India — null means any jurisdiction */
    @Column(length = 100)
    private String jurisdiction;

    /** Firm-approved/preferred clause — always injected first in RAG context */
    @Column(name = "is_golden", nullable = false)
    @Builder.Default
    private boolean golden = false;

    /** How many times this clause has been included in a draft generation */
    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    private int usageCount = 0;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    @UpdateTimestamp
    private Instant updatedAt = Instant.now();
}
