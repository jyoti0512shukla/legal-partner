package com.legalpartner.model.dto;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ClauseLibraryEntryDto {
    private UUID id;
    private String clauseType;
    private String title;
    private String content;
    private String contractType;
    private String practiceArea;
    private String industry;
    private String counterpartyType;
    private String jurisdiction;
    private boolean golden;
    private int usageCount;
    private String createdBy;
    private Instant createdAt;
}
