package com.legalpartner.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Condition evaluated before running a workflow step.
 * If the condition is false, the step is skipped.
 *
 * field: dot-path into prior results map, e.g. "RISK_ASSESSMENT.overallRisk"
 * op:    "eq", "neq", "in" (in = comma-separated values)
 * value: expected value string, e.g. "HIGH" or "HIGH,MEDIUM"
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowCondition {
    private String field;
    private String op;
    private String value;
}
