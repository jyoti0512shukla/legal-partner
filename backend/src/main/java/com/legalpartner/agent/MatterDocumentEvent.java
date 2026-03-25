package com.legalpartner.agent;

import java.util.UUID;

public record MatterDocumentEvent(UUID matterId, UUID documentId, String eventType, String triggeredBy) {}
