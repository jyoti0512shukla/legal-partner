package com.legalpartner.model.dto.matter;

import com.legalpartner.model.entity.Matter;
import java.time.Instant;
import java.util.UUID;

public record MatterResponse(
        UUID id,
        String name,
        String matterRef,
        String clientName,
        String practiceArea,
        String status,
        String description,
        String createdBy,
        Instant createdAt,
        int documentCount
) {
    public static MatterResponse from(Matter m, int documentCount) {
        return new MatterResponse(
                m.getId(), m.getName(), m.getMatterRef(), m.getClientName(),
                m.getPracticeArea() != null ? m.getPracticeArea().name() : null,
                m.getStatus().name(), m.getDescription(), m.getCreatedBy(),
                m.getCreatedAt(), documentCount
        );
    }
}
