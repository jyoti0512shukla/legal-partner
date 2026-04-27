package com.legalpartner.model.dto.extraction;

import java.util.List;

public record ExtractionEntry(
        String canonicalField,
        String rawField,
        String value,
        String bucket,
        List<EvidenceSpan> evidence,
        String extractionConfidence,
        String consistencyStatus,
        String mappingConfidence,
        String importance,
        String reasonCode,
        boolean userCorrected,
        String sectionRef
) {
    /** Confidence levels */
    public static final String HIGH = "HIGH";
    public static final String MEDIUM = "MEDIUM";
    public static final String LOW = "LOW";

    /** Consistency statuses */
    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String UNCHECKED = "UNCHECKED";
    public static final String CONFLICTING_DUPLICATES = "CONFLICTING_DUPLICATES";

    /** Reason codes for gaps */
    public static final String NOT_MENTIONED = "NOT_MENTIONED";
    public static final String UNCLEAR = "UNCLEAR";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String PARTIAL_SCAN = "PARTIAL_SCAN";
    public static final String POSSIBLE_MISSING = "POSSIBLE_MISSING";

    public ExtractionEntry withConfidence(String confidence) {
        return new ExtractionEntry(canonicalField, rawField, value, bucket, evidence,
                confidence, consistencyStatus, mappingConfidence, importance, reasonCode, userCorrected, sectionRef);
    }

    public ExtractionEntry withConsistency(String status) {
        return new ExtractionEntry(canonicalField, rawField, value, bucket, evidence,
                extractionConfidence, status, mappingConfidence, importance, reasonCode, userCorrected, sectionRef);
    }

    public ExtractionEntry withImportance(String imp) {
        return new ExtractionEntry(canonicalField, rawField, value, bucket, evidence,
                extractionConfidence, consistencyStatus, mappingConfidence, imp, reasonCode, userCorrected, sectionRef);
    }

    public ExtractionEntry withValue(String val) {
        return new ExtractionEntry(canonicalField, rawField, val, bucket, evidence,
                extractionConfidence, consistencyStatus, mappingConfidence, importance, reasonCode, true, sectionRef);
    }
}
