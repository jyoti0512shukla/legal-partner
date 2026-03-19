package com.legalpartner.model.dto;

import com.legalpartner.model.enums.WorkflowConnectorType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * A connector that fires when a workflow run completes.
 *
 * WEBHOOK config keys: url (required), secret (optional — sent as X-LegalPartner-Secret header)
 * EMAIL   config keys: recipients (required, comma-separated email addresses), subject (optional)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowConnector {
    private WorkflowConnectorType type;
    private Map<String, String> config;
}
