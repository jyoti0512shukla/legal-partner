package com.legalpartner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.DealSpec;
import com.legalpartner.model.dto.DealSpec.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a structured {@link DealSpec} from a free-text deal brief.
 *
 * Pipeline:
 *   1. LLM extraction — asks jsonChatModel for strict JSON matching the DealSpec schema
 *   2. Deterministic post-processing — regex fallbacks for values the LLM missed
 *   3. Normalisation — currency formatting, enum canonicalisation
 *
 * All fields are nullable: the extractor returns whatever it can parse and leaves
 * the rest null for graceful degradation in downstream rule evaluation.
 */
@Service
@Slf4j
public class DealSpecExtractor {

    private static final String EXTRACTION_PROMPT = """
            You are a legal deal-term extraction engine. Extract ALL structured data from the
            deal brief below into a single JSON object. Output ONLY valid JSON, no markdown.

            Schema (all fields nullable — omit or set to null if not mentioned):
            {
              "partyA": {
                "name": "full legal entity name",
                "type": "Corporation | LLC | LLP | Partnership | Individual",
                "state": "state of incorporation",
                "address": "full address",
                "role": "Licensor | Provider | Seller | Employer | Disclosing Party"
              },
              "partyB": {
                "name": "full legal entity name",
                "type": "Corporation | LLC | LLP | Partnership | Individual",
                "state": "state of incorporation",
                "address": "full address",
                "role": "Licensee | Customer | Buyer | Employee | Receiving Party"
              },
              "license": {
                "type": "perpetual | subscription | term-based",
                "users": 500,
                "locations": 3,
                "derivativeRights": true,
                "deployment": "on-premise | cloud | hybrid",
                "termDuration": "3 years"
              },
              "fees": {
                "licenseFee": 750000,
                "maintenanceFee": 150000,
                "billingCycle": "monthly | quarterly | annually",
                "currency": "USD",
                "paymentTerms": "Net 30"
              },
              "support": {
                "coverage": "24/7 | business hours | extended hours",
                "slaResponseHours": 4,
                "patchFrequency": "monthly | quarterly | as-needed",
                "uptimeSla": 99.9
              },
              "security": {
                "escrow": true,
                "soc2": true,
                "iso27001": false,
                "encryptionAtRest": true,
                "encryptionInTransit": true
              },
              "legal": {
                "jurisdiction": "State of Delaware",
                "court": "Delaware Court of Chancery",
                "arbitration": "ICC Rules",
                "liabilityCap": "12 months of fees",
                "noticeDays": 30
              },
              "customRequirements": ["source code escrow", "quarterly business reviews"]
            }

            IMPORTANT:
            - Monetary values must be plain numbers (750000 not "$750,000")
            - Extract EVERY concrete value mentioned — amounts, counts, durations, standards
            - For users/locations, extract the number only
            - If the brief mentions "perpetual" or "subscription", set license.type accordingly
            - If SOC 2, ISO 27001, escrow are mentioned, set the boolean to true
            - customRequirements: list any special terms that don't fit the structured fields

            Deal brief:
            """;

    // Regex patterns for deterministic fallback extraction
    private static final Pattern CURRENCY_PATTERN = Pattern.compile(
            "\\$([\\d,]+(?:\\.\\d{2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern USERS_PATTERN = Pattern.compile(
            "(\\d+)\\s*(?:authorized\\s+)?users?", Pattern.CASE_INSENSITIVE);
    private static final Pattern JURISDICTION_PATTERN = Pattern.compile(
            "(?:govern(?:ed|ing)\\s+(?:by\\s+)?(?:the\\s+)?laws?\\s+of\\s+(?:the\\s+)?|jurisdiction\\s+(?:of|in)\\s+(?:the\\s+)?)" +
            "([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*(?:\\s+of\\s+[A-Z][a-z]+)*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NET_TERMS_PATTERN = Pattern.compile(
            "(?:Net|net)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SLA_PATTERN = Pattern.compile(
            "(\\d+)[-\\s]*hour\\s*(?:SLA|response|response\\s*time)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPTIME_PATTERN = Pattern.compile(
            "(\\d{2,3}\\.\\d+)%?\\s*(?:uptime|availability)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATIONS_PATTERN = Pattern.compile(
            "(\\d+)\\s*(?:locations?|sites?|offices?)", Pattern.CASE_INSENSITIVE);

    private final ChatLanguageModel jsonChatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DealSpecExtractor(
            @Qualifier("jsonChatModel") ChatLanguageModel jsonChatModel) {
        this.jsonChatModel = jsonChatModel;
    }

    /**
     * Extract structured deal terms from a free-text deal brief.
     * Uses LLM with strict JSON schema, then validates with deterministic rules.
     *
     * @param dealBrief free-text description of the deal
     * @return structured DealSpec; never null, but individual fields may be null
     */
    public DealSpec extract(String dealBrief) {
        if (dealBrief == null || dealBrief.isBlank()) {
            log.debug("Empty deal brief — returning empty DealSpec");
            return DealSpec.builder().build();
        }

        DealSpec spec = extractViaLlm(dealBrief);
        spec = applyDeterministicFallbacks(spec, dealBrief);
        spec = normalise(spec);

        log.info("DealSpec extraction complete — partyA={}, partyB={}, licenseFee={}, users={}",
                spec.getPartyA() != null ? spec.getPartyA().getName() : "null",
                spec.getPartyB() != null ? spec.getPartyB().getName() : "null",
                spec.getFees() != null ? spec.getFees().getLicenseFee() : "null",
                spec.getLicense() != null ? spec.getLicense().getUsers() : "null");

        return spec;
    }

    // ── LLM extraction ──────────────────────────────────────────────────

    private DealSpec extractViaLlm(String dealBrief) {
        try {
            String prompt = EXTRACTION_PROMPT + dealBrief;
            String response = jsonChatModel.generate(UserMessage.from(prompt)).content().text().trim();

            // Strip markdown code fences if present
            String json = extractJsonBlock(response);

            DealSpec spec = objectMapper.readValue(json, DealSpec.class);
            log.debug("LLM extraction parsed successfully");
            return spec;
        } catch (Exception e) {
            log.warn("LLM extraction failed, falling back to deterministic-only: {}", e.getMessage());
            return DealSpec.builder().build();
        }
    }

    /**
     * Extract the JSON object from a response that may contain markdown fences
     * or surrounding text.
     */
    private String extractJsonBlock(String response) {
        // Try to strip ```json ... ``` fences
        if (response.contains("```")) {
            int start = response.indexOf("```");
            int jsonStart = response.indexOf('\n', start);
            int end = response.indexOf("```", jsonStart);
            if (jsonStart > 0 && end > jsonStart) {
                response = response.substring(jsonStart, end).trim();
            }
        }
        // Find the outermost { ... }
        int s = response.indexOf('{');
        int e = response.lastIndexOf('}');
        if (s >= 0 && e > s) {
            return response.substring(s, e + 1);
        }
        return response;
    }

    // ── Deterministic fallbacks ─────────────────────────────────────────

    /**
     * Fill in values that the LLM missed using regex patterns on the original brief.
     * Only sets a field if it's currently null.
     */
    private DealSpec applyDeterministicFallbacks(DealSpec spec, String brief) {
        String briefLower = brief.toLowerCase();

        // -- Fee extraction fallback --
        if (spec.getFees() == null) {
            spec.setFees(FeeTerms.builder().build());
        }
        FeeTerms fees = spec.getFees();

        if (fees.getLicenseFee() == null) {
            Long amount = extractFirstCurrencyAmount(brief);
            if (amount != null) {
                fees.setLicenseFee(amount);
                log.debug("Deterministic fallback: extracted licenseFee={}", amount);
            }
        }

        if (fees.getPaymentTerms() == null) {
            Matcher m = NET_TERMS_PATTERN.matcher(brief);
            if (m.find()) {
                fees.setPaymentTerms("Net " + m.group(1));
            }
        }

        if (fees.getCurrency() == null) {
            // Default to USD if dollar signs present, EUR for euro signs, etc.
            if (brief.contains("$")) fees.setCurrency("USD");
            else if (brief.contains("\u20AC")) fees.setCurrency("EUR");
            else if (brief.contains("\u00A3")) fees.setCurrency("GBP");
            else if (brief.contains("\u20B9")) fees.setCurrency("INR");
        }

        // -- License terms fallback --
        if (spec.getLicense() == null) {
            spec.setLicense(LicenseTerms.builder().build());
        }
        LicenseTerms license = spec.getLicense();

        if (license.getUsers() == null) {
            Matcher m = USERS_PATTERN.matcher(brief);
            if (m.find()) {
                license.setUsers(Integer.parseInt(m.group(1)));
            }
        }

        if (license.getLocations() == null) {
            Matcher m = LOCATIONS_PATTERN.matcher(brief);
            if (m.find()) {
                license.setLocations(Integer.parseInt(m.group(1)));
            }
        }

        if (license.getType() == null) {
            if (briefLower.contains("perpetual")) license.setType("perpetual");
            else if (briefLower.contains("subscription")) license.setType("subscription");
            else if (briefLower.contains("term-based") || briefLower.contains("fixed term"))
                license.setType("term-based");
        }

        if (license.getDeployment() == null) {
            if (briefLower.contains("on-premise") || briefLower.contains("on premise"))
                license.setDeployment("on-premise");
            else if (briefLower.contains("hybrid")) license.setDeployment("hybrid");
            else if (briefLower.contains("cloud") || briefLower.contains("saas"))
                license.setDeployment("cloud");
        }

        if (license.getDerivativeRights() == null) {
            if (briefLower.contains("derivative works") || briefLower.contains("derivative rights"))
                license.setDerivativeRights(true);
        }

        // -- Support terms fallback --
        if (spec.getSupport() == null) {
            spec.setSupport(SupportTerms.builder().build());
        }
        SupportTerms support = spec.getSupport();

        if (support.getSlaResponseHours() == null) {
            Matcher m = SLA_PATTERN.matcher(brief);
            if (m.find()) {
                support.setSlaResponseHours(Integer.parseInt(m.group(1)));
            }
        }

        if (support.getUptimeSla() == null) {
            Matcher m = UPTIME_PATTERN.matcher(brief);
            if (m.find()) {
                support.setUptimeSla(Double.parseDouble(m.group(1)));
            }
        }

        if (support.getCoverage() == null) {
            if (briefLower.contains("24/7") || briefLower.contains("24x7"))
                support.setCoverage("24/7");
            else if (briefLower.contains("business hours"))
                support.setCoverage("business hours");
        }

        // -- Security terms fallback --
        if (spec.getSecurity() == null) {
            spec.setSecurity(SecurityTerms.builder().build());
        }
        SecurityTerms security = spec.getSecurity();

        if (security.getEscrow() == null && briefLower.contains("escrow"))
            security.setEscrow(true);
        if (security.getSoc2() == null && (briefLower.contains("soc 2") || briefLower.contains("soc2")))
            security.setSoc2(true);
        if (security.getIso27001() == null && briefLower.contains("iso 27001"))
            security.setIso27001(true);

        // -- Legal terms fallback --
        if (spec.getLegal() == null) {
            spec.setLegal(LegalTerms.builder().build());
        }
        LegalTerms legal = spec.getLegal();

        if (legal.getJurisdiction() == null) {
            Matcher m = JURISDICTION_PATTERN.matcher(brief);
            if (m.find()) {
                legal.setJurisdiction(m.group(1).trim());
            }
        }

        if (legal.getArbitration() == null) {
            if (briefLower.contains("icc")) legal.setArbitration("ICC Rules");
            else if (briefLower.contains("aaa")) legal.setArbitration("AAA");
            else if (briefLower.contains("siac")) legal.setArbitration("SIAC Rules");
            else if (briefLower.contains("lcia")) legal.setArbitration("LCIA Rules");
        }

        return spec;
    }

    /**
     * Extract the first dollar amount from the text: $750,000 → 750000
     */
    private Long extractFirstCurrencyAmount(String text) {
        Matcher m = CURRENCY_PATTERN.matcher(text);
        if (m.find()) {
            try {
                String digits = m.group(1).replace(",", "");
                // Strip decimal cents for whole-dollar amounts
                if (digits.contains(".")) {
                    digits = digits.substring(0, digits.indexOf('.'));
                }
                return Long.parseLong(digits);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ── Normalisation ───────────────────────────────────────────────────

    /**
     * Canonicalise enum-like fields (lowercase, trim) and set sensible defaults.
     */
    private DealSpec normalise(DealSpec spec) {
        if (spec.getFees() != null && spec.getFees().getCurrency() == null) {
            spec.getFees().setCurrency("USD");
        }

        if (spec.getLicense() != null && spec.getLicense().getType() != null) {
            spec.getLicense().setType(spec.getLicense().getType().toLowerCase().trim());
        }

        if (spec.getLicense() != null && spec.getLicense().getDeployment() != null) {
            spec.getLicense().setDeployment(spec.getLicense().getDeployment().toLowerCase().trim());
        }

        return spec;
    }
}
