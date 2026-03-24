package com.legalpartner.model.dto.matter;

import java.util.UUID;

public record MatterMemberRequest(
        String email,
        String matterRole,
        UUID userId
) {}
