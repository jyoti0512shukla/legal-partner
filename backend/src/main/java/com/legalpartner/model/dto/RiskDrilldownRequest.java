package com.legalpartner.model.dto;

public record RiskDrilldownRequest(
        String categoryName,
        String rating,
        String justification,
        String sectionRef
) {}
