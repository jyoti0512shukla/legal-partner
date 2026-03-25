package com.legalpartner.model.dto.agent;

import java.time.Instant;
import java.util.UUID;

public record PlaybookDto(UUID id, String name, String dealType, String description,
                          boolean isDefault, int positionCount, Instant createdAt) {}
