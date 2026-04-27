package com.legalpartner.model.dto.extraction;

public record EvidenceSpan(
        String text,
        int charStart,
        int charEnd
) {}
