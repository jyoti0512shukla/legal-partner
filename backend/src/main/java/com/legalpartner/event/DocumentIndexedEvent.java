package com.legalpartner.event;

import java.util.UUID;

/**
 * Published (via ApplicationEventPublisher) when a document finishes indexing.
 * WorkflowTriggerService listens to this to auto-run configured workflows.
 */
public record DocumentIndexedEvent(UUID documentId, String uploadedBy, String fileName) {}
