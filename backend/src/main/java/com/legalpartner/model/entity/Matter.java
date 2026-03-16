package com.legalpartner.model.entity;

import com.legalpartner.model.enums.MatterStatus;
import com.legalpartner.model.enums.PracticeArea;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "matters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Matter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String matterRef;          // e.g. "2024-CORP-001"

    @Column(nullable = false, length = 255)
    private String clientName;

    @Enumerated(EnumType.STRING)
    @Column(length = 100)
    private PracticeArea practiceArea;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private MatterStatus status = MatterStatus.ACTIVE;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 255)
    private String createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @UpdateTimestamp
    private Instant updatedAt;
}
