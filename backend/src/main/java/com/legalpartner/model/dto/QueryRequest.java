package com.legalpartner.model.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
        @NotBlank String query,
        String jurisdiction,
        Integer year,
        String clauseType,
        String practiceArea,
        String clientName,
        String conversationId,  // null for new conversation; UUID string for follow-up queries
        String matterId         // optional: scope query to documents in this matter
) {}
