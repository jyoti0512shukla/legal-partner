package com.legalpartner.model.dto;

import java.time.Instant;
import java.util.UUID;

public record TeamDto(UUID id, String name, String description, int memberCount, Instant createdAt) {}
