package com.legalpartner.model.dto;

public record ClauseCheckResult(
        String clauseName,
        String status,          // PRESENT, MISSING, WEAK
        String foundText,       // null if missing
        String sectionRef,      // "Section X.Y" or "MISSING"
        String riskLevel,       // HIGH, MEDIUM, LOW
        String assessment,
        String recommendation
) {}
