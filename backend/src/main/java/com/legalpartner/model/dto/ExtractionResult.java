package com.legalpartner.model.dto;

public record ExtractionResult(
        String partyA,
        String partyB,
        String effectiveDate,
        String expiryDate,
        String contractValue,
        String liabilityCap,
        String governingLaw,
        String noticePeriodDays,
        String arbitrationVenue
) {}
