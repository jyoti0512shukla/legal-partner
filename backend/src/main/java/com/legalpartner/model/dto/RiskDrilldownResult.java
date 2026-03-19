package com.legalpartner.model.dto;

public record RiskDrilldownResult(
        String categoryName,
        String rating,
        String detailedRisk,
        String businessImpact,
        String howToFix,
        String suggestedLanguage
) {}
