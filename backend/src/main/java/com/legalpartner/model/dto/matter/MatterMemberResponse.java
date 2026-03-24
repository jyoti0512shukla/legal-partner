package com.legalpartner.model.dto.matter;

import java.time.Instant;
import java.util.UUID;

public record MatterMemberResponse(
        UUID id,
        UUID userId,
        String email,
        String displayName,
        String matterRole,
        Instant addedAt
) {}
