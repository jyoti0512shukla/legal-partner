package com.legalpartner.model.dto;

import com.legalpartner.model.enums.WorkflowStepType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStepConfig {
    private WorkflowStepType type;
    private String label;
}
