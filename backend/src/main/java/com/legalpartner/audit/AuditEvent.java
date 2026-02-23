package com.legalpartner.audit;

import com.legalpartner.model.enums.AuditActionType;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class AuditEvent {
    private String username;
    private String userRole;
    private AuditActionType action;
    private String endpoint;
    private String httpMethod;
    private UUID documentId;
    private String queryText;
    private String retrievedDocIds;
    private Long responseTimeMs;
    private String ipAddress;
    private boolean success;
    private String errorMessage;
}
