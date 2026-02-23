package com.legalpartner.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CompareRequest(
        @NotNull UUID documentId1,
        @NotNull UUID documentId2
) {}
