package com.legalpartner.model.dto;

import java.time.Instant;
import java.util.UUID;

public record TeamMemberDto(UUID id, UUID userId, String email, String displayName, String role, Instant addedAt) {}
