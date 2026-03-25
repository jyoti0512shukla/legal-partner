package com.legalpartner.model.dto.auth;

import java.time.Instant;
import java.util.UUID;
public record UserAdminDto(UUID id, String email, String displayName, String role,
                           String accountStatus, boolean enabled, boolean mfaEnabled,
                           Instant lastLoginAt, Instant createdAt) {}
