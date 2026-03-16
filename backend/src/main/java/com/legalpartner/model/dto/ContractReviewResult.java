package com.legalpartner.model.dto;

import java.util.List;

public record ContractReviewResult(
        String documentName,
        String overallRisk,         // HIGH, MEDIUM, LOW
        int clausesPresent,
        int clausesMissing,
        int clausesWeak,
        List<ClauseCheckResult> clauses,
        List<String> criticalMissingClauses,
        List<String> recommendations
) {}
