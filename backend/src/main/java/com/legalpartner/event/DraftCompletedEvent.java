package com.legalpartner.event;

import java.util.UUID;

/**
 * Published when a draft contract generation completes successfully.
 * WorkflowTriggerService listens to this to auto-run DRAFT_COMPLETED workflows.
 */
public record DraftCompletedEvent(UUID documentId, String username, String contractType) {}
