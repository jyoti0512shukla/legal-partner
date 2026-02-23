package com.legalpartner.model.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditLogEntry(
        UUID id,
        Instant timestamp,
        String username,
        String userRole,
        String action,
        String endpoint,
        UUID documentId,
        String queryText,
        Long responseTimeMs,
        boolean success,
        String errorMessage
) {}
