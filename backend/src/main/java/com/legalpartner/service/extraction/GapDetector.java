package com.legalpartner.service.extraction;

import com.legalpartner.model.dto.extraction.ContractTypeDetection;
import com.legalpartner.model.dto.extraction.ExtractionEntry;
import com.legalpartner.service.RiskQuestionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects missing fields based on contract type requirements.
 * Confidence gate: >0.7 full check, 0.5-0.7 soft mode (POSSIBLE_MISSING), <0.5 skip.
 * Distinguishes: NOT_MENTIONED, UNCLEAR, NOT_APPLICABLE, POSSIBLE_MISSING.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GapDetector {

    private final RiskQuestionEngine riskQuestionEngine;
    private final ConsistencyChecker consistencyChecker;

    /**
     * Detect gaps — required fields not found in extractions.
     * @param entries current extraction entries
     * @param typeDetection detected contract type with confidence
     * @param fullText full document text (for keyword presence checks)
     */
    public List<ExtractionEntry> detect(
            List<ExtractionEntry> entries,
            ContractTypeDetection typeDetection,
            String fullText) {

        if (typeDetection.isLowConfidence()) {
            log.info("Gap check skipped — contract type confidence too low ({})", typeDetection.confidence());
            return entries;
        }

        Set<String> discoveredFields = entries.stream()
                .map(ExtractionEntry::canonicalField)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Get required fields for this contract type
        List<String> requiredClauses = riskQuestionEngine.getRequiredClauses(typeDetection.contractType());
        List<RiskQuestionEngine.ExtractionField> extractionFields =
                riskQuestionEngine.getExtractionFields(typeDetection.contractType());

        Set<String> requiredFieldIds = new HashSet<>();
        for (var f : extractionFields) {
            requiredFieldIds.add(f.id().toLowerCase().replace("-", "_"));
        }
        for (String clause : requiredClauses) {
            requiredFieldIds.add(clause.toLowerCase().replace("-", "_"));
        }

        String lowerText = fullText.toLowerCase();
        boolean isSoftMode = !typeDetection.isHighConfidence(); // 0.5-0.7 range
        List<ExtractionEntry> result = new ArrayList<>(entries);

        for (String requiredField : requiredFieldIds) {
            if (discoveredFields.contains(requiredField)) continue;

            // Determine reason code
            String reasonCode;
            if (isSoftMode) {
                reasonCode = ExtractionEntry.POSSIBLE_MISSING;
            } else {
                // Check if any keywords related to this field appear in the text
                boolean mentioned = isFieldMentioned(requiredField, lowerText);
                reasonCode = mentioned ? ExtractionEntry.UNCLEAR : ExtractionEntry.NOT_MENTIONED;
            }

            String bucket = consistencyChecker.getBucket(requiredField);
            result.add(new ExtractionEntry(
                    requiredField, null, null, bucket,
                    List.of(), ExtractionEntry.LOW, ExtractionEntry.UNCHECKED,
                    ExtractionEntry.HIGH, // mapping is certain since it's from config
                    ExtractionEntry.LOW,  // importance defaults LOW, ranker will upgrade if critical
                    reasonCode, false, null
            ));
        }

        int gapsAdded = result.size() - entries.size();
        log.info("Gap detection: {} gaps found for contract type {} (confidence={}, softMode={})",
                gapsAdded, typeDetection.contractType(), typeDetection.confidence(), isSoftMode);
        return result;
    }

    /** Check if any keywords associated with a field appear in the document text */
    private boolean isFieldMentioned(String fieldId, String lowerText) {
        // Map common field IDs to search keywords
        Map<String, String[]> fieldKeywords = Map.ofEntries(
                Map.entry("liability", new String[]{"liability", "limitation of liability", "cap"}),
                Map.entry("liability_cap", new String[]{"liability", "limitation of liability", "cap"}),
                Map.entry("indemnification", new String[]{"indemnif", "hold harmless"}),
                Map.entry("indemnity", new String[]{"indemnif", "hold harmless"}),
                Map.entry("termination", new String[]{"terminat", "expiry"}),
                Map.entry("termination_clause", new String[]{"terminat", "expiry"}),
                Map.entry("confidentiality", new String[]{"confidential", "non-disclosure"}),
                Map.entry("confidentiality_term", new String[]{"confidential", "non-disclosure"}),
                Map.entry("ip_rights", new String[]{"intellectual property", "ip rights", "copyright"}),
                Map.entry("ip_ownership", new String[]{"intellectual property", "ip rights", "copyright"}),
                Map.entry("governing_law", new String[]{"governing law", "jurisdiction"}),
                Map.entry("force_majeure", new String[]{"force majeure", "act of god"}),
                Map.entry("data_protection", new String[]{"data protection", "personal data", "gdpr"}),
                Map.entry("payment", new String[]{"payment", "fee", "compensation"}),
                Map.entry("payment_terms", new String[]{"payment", "fee", "invoice"}),
                Map.entry("warranties", new String[]{"warrant", "representation"})
        );

        String[] keywords = fieldKeywords.get(fieldId);
        if (keywords == null) {
            // Fallback: check if the field name itself appears
            return lowerText.contains(fieldId.replace("_", " "));
        }
        for (String kw : keywords) {
            if (lowerText.contains(kw)) return true;
        }
        return false;
    }
}
