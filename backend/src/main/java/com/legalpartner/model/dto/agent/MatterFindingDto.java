package com.legalpartner.model.dto.agent;

import java.time.Instant;
import java.util.UUID;

public record MatterFindingDto(UUID id, UUID matterId, UUID documentId, String documentName,
                               String findingType, String severity, String clauseType,
                               String title, String description, String sectionRef,
                               UUID relatedDocumentId, String relatedDocumentName,
                               String status, UUID reviewedBy, Instant reviewedAt,
                               Instant createdAt) {}
