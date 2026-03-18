package com.legalpartner.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftRequest {

    @NotBlank(message = "Template ID is required")
    private String templateId;

    private String partyA;
    private String partyB;
    private String partyAAddress;
    private String partyBAddress;
    private String partyARep;
    private String partyBRep;
    private String effectiveDate;
    private String jurisdiction;
    private String agreementRef;
    private String termYears;
    private String noticeDays;
    private String survivalYears;
    private String practiceArea;
    private String counterpartyType;

    /** Free-text deal brief — injected verbatim into every clause prompt. */
    private String dealBrief;

    /**
     * Which party the firm represents.
     * Values: PARTY_A | PARTY_B | NEUTRAL
     */
    private String clientPosition;

    /**
     * Industry sector for regulatory references.
     * Values: FINTECH | PHARMA | IT_SERVICES | MANUFACTURING | GENERAL
     */
    private String industry;

    /**
     * Negotiation stance affecting drafting bias.
     * Values: FIRST_DRAFT | BALANCED | FINAL_OFFER
     */
    private String draftStance;
}
