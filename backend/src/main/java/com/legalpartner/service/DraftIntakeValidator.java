package com.legalpartner.service;

import com.legalpartner.config.ContractTypeRegistry;
import com.legalpartner.model.dto.DealSpec;
import com.legalpartner.model.dto.DraftRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Validates that a draft request has all required information before generation.
 * Extracts DealSpec from the deal brief, merges with form fields, identifies gaps.
 *
 * Flow:
 *   1. Extract DealSpec from deal brief (LLM call)
 *   2. Merge with form fields (jurisdiction from dropdown, etc.)
 *   3. Check required_fields from contract_types.yml
 *   4. Return: ready to generate, or list of missing/recommended fields
 */
@Service
@Slf4j
public class DraftIntakeValidator {

    private final ContractTypeRegistry contractRegistry;
    private final DealSpecExtractor dealSpecExtractor;

    /** Human-readable labels for DealSpec field paths. */
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("partyA.name", "Party A name"),
            Map.entry("partyB.name", "Party B name"),
            Map.entry("partyA.address", "Party A address"),
            Map.entry("partyB.address", "Party B address"),
            Map.entry("legal.jurisdiction", "Governing law jurisdiction"),
            Map.entry("legal.court", "Court for disputes"),
            Map.entry("legal.noticeDays", "Notice period (days)"),
            Map.entry("legal.survivalYears", "Survival period (years)"),
            Map.entry("license.type", "License type (perpetual/subscription)"),
            Map.entry("license.users", "Number of authorized users"),
            Map.entry("license.locations", "Number of licensed sites"),
            Map.entry("fees.licenseFee", "License fee amount"),
            Map.entry("fees.maintenanceFee", "Annual maintenance fee"),
            Map.entry("fees.subscriptionFee", "Subscription fee"),
            Map.entry("fees.billingCycle", "Billing cycle (monthly/annual)"),
            Map.entry("support.slaResponseHours", "SLA response time (hours)"),
            Map.entry("support.coverage", "Support coverage (24/7/business hours)"),
            Map.entry("support.uptimeSla", "Uptime SLA percentage"),
            Map.entry("support.patchFrequency", "Security patch frequency"),
            Map.entry("security.escrow", "Source code escrow required"),
            Map.entry("compensation.salary", "Annual salary/compensation"),
            Map.entry("legal.noticePeriod", "Employment notice period")
    );

    public DraftIntakeValidator(ContractTypeRegistry contractRegistry, DealSpecExtractor dealSpecExtractor) {
        this.contractRegistry = contractRegistry;
        this.dealSpecExtractor = dealSpecExtractor;
    }

    /**
     * Validate a draft request. Extracts DealSpec from brief, merges with form fields,
     * returns completeness assessment.
     */
    public ValidationResult validate(DraftRequest request) {
        // Step 1: Extract DealSpec from deal brief
        String brief = request.getDealBrief() != null ? request.getDealBrief() : request.getDealContext();
        DealSpec dealSpec = null;
        if (brief != null && !brief.isBlank()) {
            dealSpec = dealSpecExtractor.extract(brief);
        }

        // Step 2: Merge form fields into DealSpec (form takes priority)
        dealSpec = mergeFormFields(dealSpec, request);

        // Step 3: Get required/recommended fields for this contract type
        var config = contractRegistry.get(request.getTemplateId());
        List<String> requiredFields = config != null && config.requiredFields() != null
                ? config.requiredFields() : List.of();
        List<String> recommendedFields = config != null && config.recommendedFields() != null
                ? config.recommendedFields() : List.of();

        // Step 4: Check what's missing
        List<MissingField> missing = new ArrayList<>();
        List<MissingField> recommended = new ArrayList<>();

        for (String field : requiredFields) {
            if (!hasValue(dealSpec, field, request)) {
                missing.add(new MissingField(field, getLabel(field), true));
            }
        }
        for (String field : recommendedFields) {
            if (!hasValue(dealSpec, field, request)) {
                recommended.add(new MissingField(field, getLabel(field), false));
            }
        }

        // Step 5: Build result
        boolean ready = missing.isEmpty();
        log.info("Draft intake validation: template={}, ready={}, missing={}, recommended={}",
                request.getTemplateId(), ready, missing.size(), recommended.size());

        return new ValidationResult(ready, dealSpec, missing, recommended);
    }

    /** Check if a field has a value from either DealSpec or form fields. */
    private boolean hasValue(DealSpec dealSpec, String fieldPath, DraftRequest request) {
        // Check form fields first
        switch (fieldPath) {
            case "partyA.name":
                if (request.getPartyA() != null && !request.getPartyA().isBlank()) return true;
                break;
            case "partyB.name":
                if (request.getPartyB() != null && !request.getPartyB().isBlank()) return true;
                break;
            case "legal.jurisdiction":
                if (request.getJurisdiction() != null && !request.getJurisdiction().isBlank()) return true;
                break;
        }

        // Check DealSpec
        if (dealSpec == null) return false;
        Object value = dealSpec.resolveField(fieldPath);
        if (value == null) return false;
        if (value instanceof String s) return !s.isBlank();
        if (value instanceof Number n) return n.longValue() != 0;
        if (value instanceof Boolean) return true;
        return true;
    }

    /** Merge form fields into DealSpec — form values take priority over extracted. */
    private DealSpec mergeFormFields(DealSpec dealSpec, DraftRequest request) {
        if (dealSpec == null) {
            dealSpec = DealSpec.builder().build();
        }

        // Merge party names from form if not in DealSpec
        if (request.getPartyA() != null && !request.getPartyA().isBlank()) {
            if (dealSpec.getPartyA() == null) {
                dealSpec.setPartyA(DealSpec.PartyInfo.builder().name(request.getPartyA()).build());
            } else if (dealSpec.getPartyA().getName() == null || dealSpec.getPartyA().getName().isBlank()) {
                dealSpec.getPartyA().setName(request.getPartyA());
            }
        }
        if (request.getPartyB() != null && !request.getPartyB().isBlank()) {
            if (dealSpec.getPartyB() == null) {
                dealSpec.setPartyB(DealSpec.PartyInfo.builder().name(request.getPartyB()).build());
            } else if (dealSpec.getPartyB().getName() == null || dealSpec.getPartyB().getName().isBlank()) {
                dealSpec.getPartyB().setName(request.getPartyB());
            }
        }

        // Merge jurisdiction from form
        if (request.getJurisdiction() != null && !request.getJurisdiction().isBlank()) {
            if (dealSpec.getLegal() == null) {
                dealSpec.setLegal(DealSpec.LegalTerms.builder().jurisdiction(request.getJurisdiction()).build());
            } else if (dealSpec.getLegal().getJurisdiction() == null || dealSpec.getLegal().getJurisdiction().isBlank()) {
                dealSpec.getLegal().setJurisdiction(request.getJurisdiction());
            }
        }

        return dealSpec;
    }

    private String getLabel(String fieldPath) {
        return FIELD_LABELS.getOrDefault(fieldPath, fieldPath);
    }

    // ── Result types ──

    public record ValidationResult(
            boolean ready,
            DealSpec dealSpec,
            List<MissingField> missingRequired,
            List<MissingField> missingRecommended
    ) {}

    public record MissingField(
            String fieldPath,
            String label,
            boolean required
    ) {}
}
