package com.legalpartner.event;

import java.util.UUID;

public record DocumentExecutedEvent(UUID documentId, String username) {}
