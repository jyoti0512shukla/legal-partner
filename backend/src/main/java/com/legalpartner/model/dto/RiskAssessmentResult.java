package com.legalpartner.model.dto;

import java.util.List;

public record RiskAssessmentResult(
        String overallRisk,
        List<RiskCategory> categories
) {}
