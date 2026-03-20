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
    /** Number of extra retry attempts on exception (0 = no retries) */
    @Builder.Default
    private int retryCount = 0;
    /**
     * Max quality-loop iterations for this step (1 = no loop, 2-3 = refine until quality passes).
     * Quality is scored structurally (no extra LLM call). If score < 70, the step is re-run
     * with a feedback prompt listing specific gaps to address.
     */
    @Builder.Default
    private int maxIterations = 1;
    /** Step-type-specific params (e.g. clauseType=LIABILITY for DRAFT_CLAUSE) */
    private java.util.Map<String, String> params;
}
