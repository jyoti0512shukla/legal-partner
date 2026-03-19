package com.legalpartner.model.dto;

import com.legalpartner.model.enums.WorkflowStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunDto {
    private UUID id;
    private UUID workflowDefinitionId;
    private String workflowName;
    private UUID documentId;
    private String username;
    private WorkflowStatus status;
    private int currentStep;
    private int totalSteps;
    private Map<String, Object> results;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;
}
