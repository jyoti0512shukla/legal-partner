package com.legalpartner.model.enums;

public enum WorkflowTriggerEvent {
    /** When a new document is uploaded and indexed */
    DOCUMENT_INDEXED,
    /** When a draft contract is generated */
    DRAFT_COMPLETED,
    /** When risk assessment rates overall HIGH */
    RISK_HIGH_DETECTED,
    /** When a new matter is created */
    MATTER_CREATED
}
