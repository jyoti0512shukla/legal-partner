package com.legalpartner.model.dto;

import com.legalpartner.model.enums.WorkflowTriggerEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTrigger {
    private WorkflowTriggerEvent event;
    /** Optional filter — e.g., contract type name for DOCUMENT_INDEXED */
    private String filter;
}
