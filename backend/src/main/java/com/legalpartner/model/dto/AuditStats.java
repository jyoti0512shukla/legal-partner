package com.legalpartner.model.dto;

import java.util.Map;

public record AuditStats(
        long totalActions,
        long uploads,
        long queries,
        long comparisons,
        long riskAssessments,
        Map<String, Long> actionsByUser,
        Map<String, Long> actionsByDay
) {}
