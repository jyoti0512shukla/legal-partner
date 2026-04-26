package com.legalpartner.model.enums;

public enum WorkflowStepType {
    EXTRACT_KEY_TERMS,
    RISK_ASSESSMENT,
    GENERATE_SUMMARY,
    REDLINE_SUGGESTIONS,
    /** Draft a specific clause using RAG corpus + clause library, then loop-refine */
    DRAFT_CLAUSE,
    /** Validate contract against selected playbook rules */
    COMPLIANCE_CHECK,
    /** Extract obligations: deadlines, payments, renewals, notice periods */
    OBLIGATION_EXTRACT,
    /** Human-in-the-loop: pause workflow, assign to approver, resume on approve/reject */
    APPROVAL_GATE
}
