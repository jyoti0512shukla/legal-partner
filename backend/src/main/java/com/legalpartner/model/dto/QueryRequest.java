package com.legalpartner.model.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
        @NotBlank String query,
        String jurisdiction,
        Integer year,
        String clauseType,
        String practiceArea,
        String clientName
) {}
