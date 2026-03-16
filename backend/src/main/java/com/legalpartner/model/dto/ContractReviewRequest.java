package com.legalpartner.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ContractReviewRequest(
        @NotNull UUID documentId,
        String checklistType    // "STANDARD" (default), "NDA", "EMPLOYMENT" — reserved for future
) {}
