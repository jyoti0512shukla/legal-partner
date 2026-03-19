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
    /** Optional: skip this step unless condition is met */
    private WorkflowCondition condition;
    /** Number of extra retry attempts on failure (0 = no retries) */
    @Builder.Default
    private int retryCount = 0;
}
