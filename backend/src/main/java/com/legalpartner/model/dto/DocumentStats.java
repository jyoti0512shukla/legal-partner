package com.legalpartner.model.dto;

import java.util.Map;

public record DocumentStats(
        long totalDocuments,
        long totalSegments,
        Map<String, Integer> segmentsByClauseType,
        Map<String, Integer> documentsByJurisdiction,
        Map<String, Integer> documentsByPracticeArea
) {}
