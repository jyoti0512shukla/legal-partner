package com.legalpartner.model.dto;

public record RiskCategory(
        String name,
        String rating,
        String justification,
        String clauseReference
) {}
