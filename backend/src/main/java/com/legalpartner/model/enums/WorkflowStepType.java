package com.legalpartner.model.enums;

public enum WorkflowStepType {
    EXTRACT_KEY_TERMS,
    RISK_ASSESSMENT,
    CLAUSE_CHECKLIST,
    GENERATE_SUMMARY,
    REDLINE_SUGGESTIONS,
    /** Draft a specific clause using RAG corpus + clause library, then loop-refine */
    DRAFT_CLAUSE,
    SEND_FOR_SIGNATURE
}
