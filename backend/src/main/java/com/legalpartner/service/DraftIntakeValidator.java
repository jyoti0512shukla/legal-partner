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

        // Merge survival / notice from form
        if (request.getSurvivalYears() != null && !request.getSurvivalYears().isBlank()) {
            if (dealSpec.getLegal() == null) dealSpec.setLegal(DealSpec.LegalTerms.builder().build());
            if (dealSpec.getLegal().getSurvivalYears() == null) {
                try { dealSpec.getLegal().setSurvivalYears(Integer.parseInt(request.getSurvivalYears().trim())); }
                catch (NumberFormatException ignored) { }
            }
        }
        if (request.getNoticeDays() != null && !request.getNoticeDays().isBlank()) {
            if (dealSpec.getLegal() == null) dealSpec.setLegal(DealSpec.LegalTerms.builder().build());
            if (dealSpec.getLegal().getNoticeDays() == null) {
                try { dealSpec.getLegal().setNoticeDays(Integer.parseInt(request.getNoticeDays().trim())); }
                catch (NumberFormatException ignored) { }
            }
        }

        return dealSpec;
    }

    private String getLabel(String fieldPath) {
        return contractRegistry.fieldLabel(fieldPath);
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
