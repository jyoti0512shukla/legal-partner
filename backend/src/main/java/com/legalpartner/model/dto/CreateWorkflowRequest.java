package com.legalpartner.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CreateWorkflowRequest {
    @NotBlank
    private String name;
    private String description;
    @NotEmpty
    private List<WorkflowStepConfig> steps;
    private List<WorkflowConnector> connectors;
    private boolean autoTrigger;
    private boolean team;
}
