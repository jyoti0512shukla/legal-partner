package com.legalpartner.model.dto.matter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MatterRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100) String matterRef,
        @NotBlank @Size(max = 255) String clientName,
        String practiceArea,
        String description,
        String dealType,
        java.util.UUID defaultPlaybookId
) {}
