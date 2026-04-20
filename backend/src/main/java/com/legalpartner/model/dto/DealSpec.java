package com.legalpartner.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Structured deal truth — the single source of truth for all commercial,
 * legal, and operational terms extracted from a free-text deal brief.
 *
 * Every field is nullable: the LLM may not extract everything from the brief,
 * and the system degrades gracefully when fields are missing.
 *
 * Used by:
 *   - {@code DealSpecExtractor} to populate from free text
 *   - {@code ClauseRuleEngine} to validate clauses against deal terms
 *   - {@code FixEngine} to inject missing values deterministically
 *   - {@code DealCoverageScore} to compute coverage metrics
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DealSpec {

    private PartyInfo partyA;
    private PartyInfo partyB;
    private LicenseTerms license;
    private FeeTerms fees;
    private SupportTerms support;
    private SecurityTerms security;
    private LegalTerms legal;
    private List<String> customRequirements;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PartyInfo {
        private String name;
        /** Corporation, LLC, LLP, Partnership, Individual */
        private String type;
        /** State / province of incorporation */
        private String state;
        private String address;
        /** Role in the contract: Licensor, Licensee, Provider, Customer, etc. */
        private String role;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LicenseTerms {
        /** perpetual, subscription, term-based */
        private String type;
        /** Number of authorized users */
        private Integer users;
        /** Number of authorized locations / sites */
        private Integer locations;
        /** Whether derivative works are permitted */
        @JsonProperty("derivativeRights")
        private Boolean derivativeRights;
        /** on-premise, cloud, hybrid */
        private String deployment;
        /** Term duration, e.g. "3 years", "12 months" */
        private String termDuration;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FeeTerms {
        /** One-time license fee in minor units (cents) or whole units */
        private Long licenseFee;
        /** Annual / periodic maintenance fee */
        private Long maintenanceFee;
        /** monthly, quarterly, annually */
        private String billingCycle;
        /** ISO 4217 currency code — defaults to USD */
        private String currency;
        /** Payment terms, e.g. "Net 30", "Net 45" */
        private String paymentTerms;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SupportTerms {
        /** 24/7, business hours (9-5), extended hours */
        private String coverage;
        /** Max hours for initial SLA response */
        private Integer slaResponseHours;
        /** monthly, quarterly, as-needed */
        private String patchFrequency;
        /** Uptime SLA percentage, e.g. 99.9 */
        private Double uptimeSla;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SecurityTerms {
        /** Source code escrow required */
        private Boolean escrow;
        /** SOC 2 Type II compliance */
        private Boolean soc2;
        /** ISO 27001 certification */
        private Boolean iso27001;
        /** Data encryption at rest required */
        private Boolean encryptionAtRest;
        /** Data encryption in transit required */
        private Boolean encryptionInTransit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LegalTerms {
        /** Governing law jurisdiction, e.g. "State of Delaware" */
        private String jurisdiction;
        /** Court for disputes */
        private String court;
        /** Arbitration body / rules, e.g. "ICC Rules", "AAA" */
        private String arbitration;
        /** Liability cap expression, e.g. "12 months of fees" */
        private String liabilityCap;
        /** Notice period in days */
        private Integer noticeDays;
    }

    // ── Convenience accessors for rule engine field resolution ────────

    /**
     * Resolve a dotted field path (e.g. "fees.licenseFee", "legal.jurisdiction")
     * to its value. Returns null if any segment is null.
     */
    public Object resolveField(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) return null;
        String[] parts = fieldPath.split("\\.");
        if (parts.length != 2) return null;

        Object section = switch (parts[0]) {
            case "partyA" -> partyA;
            case "partyB" -> partyB;
            case "license" -> license;
            case "fees" -> fees;
            case "support" -> support;
            case "security" -> security;
            case "legal" -> legal;
            default -> null;
        };
        if (section == null) return null;

        try {
            var field = section.getClass().getDeclaredField(parts[1]);
            field.setAccessible(true);
            return field.get(section);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Format a monetary value for human-readable display: $750,000
     */
    public static String formatCurrency(Long amount, String currency) {
        if (amount == null) return null;
        NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        String symbol = (currency != null && currency.equalsIgnoreCase("EUR")) ? "\u20AC"
                : (currency != null && currency.equalsIgnoreCase("GBP")) ? "\u00A3"
                : (currency != null && currency.equalsIgnoreCase("INR")) ? "\u20B9"
                : "$";
        return symbol + fmt.format(amount);
    }

    /**
     * Resolve a field and return its formatted string representation.
     * Monetary fields get currency formatting; others get toString().
     */
    public String resolveFieldFormatted(String fieldPath) {
        Object val = resolveField(fieldPath);
        if (val == null) return null;

        // Format monetary fields
        if (fieldPath.endsWith("Fee") || fieldPath.endsWith("fee")) {
            if (val instanceof Long l) {
                String curr = fees != null ? fees.getCurrency() : "USD";
                return formatCurrency(l, curr);
            }
            if (val instanceof Number n) {
                String curr = fees != null ? fees.getCurrency() : "USD";
                return formatCurrency(n.longValue(), curr);
            }
        }

        if (val instanceof Boolean b) {
            return b ? "required" : "not required";
        }
        return val.toString();
    }
}
