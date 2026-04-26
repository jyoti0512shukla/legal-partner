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
    private boolean team;
    private boolean autoTrigger;
    private List<WorkflowTrigger> triggers;
    private List<WorkflowStepConfig> steps;
    private List<WorkflowConnector> connectors;
    private String createdBy;
    private Instant createdAt;
}
