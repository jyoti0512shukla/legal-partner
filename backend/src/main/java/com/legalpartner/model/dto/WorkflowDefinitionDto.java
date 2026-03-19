package com.legalpartner.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDefinitionDto {
    private UUID id;
    private String name;
    private String description;
    private boolean predefined;
    private List<WorkflowStepConfig> steps;
    private String createdBy;
    private Instant createdAt;
}
