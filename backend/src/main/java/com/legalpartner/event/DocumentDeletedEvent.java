package com.legalpartner.event;

import java.util.UUID;

/** Published after a document and its indexed data are deleted; learning stores clean up on it. */
public record DocumentDeletedEvent(UUID documentId, String username) {}
