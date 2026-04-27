package com.legalpartner.service.extraction;

import com.legalpartner.model.dto.extraction.EvidenceSpan;
import com.legalpartner.model.dto.extraction.ExtractionEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates extraction evidence against the full document text.
 * LLM returns evidence TEXT only — offsets computed here against the FULL document.
 * Normalized matching handles OCR noise, smart quotes, whitespace differences.
 */
@Component
@Slf4j
public class EvidenceValidator {

    /**
     * Validate evidence for all entries against the full document text.
     * Computes char offsets, type-checks values, sets extraction confidence.
     */
    public List<ExtractionEntry> validate(List<ExtractionEntry> entries, String fullText) {
        String normalizedFull = normalizeForMatching(fullText);
        List<ExtractionEntry> validated = new ArrayList<>();

        for (ExtractionEntry entry : entries) {
            if (entry.reasonCode() != null) {
                // Gap entries — no evidence to validate
                validated.add(entry);
                continue;
            }

            List<EvidenceSpan> validatedSpans = new ArrayList<>();
            boolean anyValidated = false;

            if (entry.evidence() != null) {
                for (EvidenceSpan span : entry.evidence()) {
                    if (span.text() == null || span.text().isBlank()) continue;

                    EvidenceSpan resolved = resolveOffset(span.text(), fullText, normalizedFull, entry.sectionRef());
                    if (resolved != null) {
                        validatedSpans.add(resolved);
                        anyValidated = true;
                    } else {
                        // Keep the evidence text but with -1 offsets (unresolved)
                        validatedSpans.add(new EvidenceSpan(span.text(), -1, -1));
                    }
                }
            }

            // Type check the value
            boolean typeValid = typeCheck(entry.canonicalField(), entry.value());

            // Determine confidence
            String confidence;
            if (anyValidated && typeValid) {
                confidence = ExtractionEntry.HIGH;
            } else if (anyValidated || typeValid) {
                confidence = ExtractionEntry.MEDIUM;
            } else if (!validatedSpans.isEmpty()) {
                confidence = ExtractionEntry.MEDIUM; // Has evidence text but couldn't locate in doc
            } else {
                confidence = ExtractionEntry.LOW;
            }

            validated.add(new ExtractionEntry(
                    entry.canonicalField(), entry.rawField(), entry.value(), entry.bucket(),
                    validatedSpans, confidence, entry.consistencyStatus(),
                    entry.mappingConfidence(), entry.importance(), entry.reasonCode(),
                    entry.userCorrected(), entry.sectionRef()
            ));
        }

        return validated;
    }

    /**
     * Resolve the char offsets of an evidence quote in the full document.
     * Uses normalized matching, falls back to longest common substring.
     */
    private EvidenceSpan resolveOffset(String evidenceText, String fullText, String normalizedFull, String sectionRef) {
        String normalizedEvidence = normalizeForMatching(evidenceText);

        // Direct normalized substring match
        int idx = normalizedFull.indexOf(normalizedEvidence);
        if (idx >= 0) {
            // Map back to original text positions
            int origStart = mapNormalizedOffsetToOriginal(fullText, normalizedFull, idx);
            int origEnd = mapNormalizedOffsetToOriginal(fullText, normalizedFull, idx + normalizedEvidence.length());
            return new EvidenceSpan(fullText.substring(origStart, Math.min(origEnd, fullText.length())), origStart, origEnd);
        }

        // Multiple matches — find closest to sectionRef
        List<Integer> allMatches = findAllOccurrences(normalizedFull, normalizedEvidence);
        if (allMatches.size() > 1 && sectionRef != null) {
            String normSection = normalizeForMatching(sectionRef);
            int sectionIdx = normalizedFull.indexOf(normSection);
            if (sectionIdx >= 0) {
                int closest = allMatches.stream().min((a, b) ->
                        Math.abs(a - sectionIdx) - Math.abs(b - sectionIdx)).orElse(allMatches.get(0));
                int origStart = mapNormalizedOffsetToOriginal(fullText, normalizedFull, closest);
                int origEnd = mapNormalizedOffsetToOriginal(fullText, normalizedFull, closest + normalizedEvidence.length());
                return new EvidenceSpan(fullText.substring(origStart, Math.min(origEnd, fullText.length())), origStart, origEnd);
            }
        }

        // Fallback: longest common substring (min 20 chars)
        String lcs = longestCommonSubstring(normalizedFull, normalizedEvidence, 20);
        if (lcs != null) {
            int lcsIdx = normalizedFull.indexOf(lcs);
            if (lcsIdx >= 0) {
                int origStart = mapNormalizedOffsetToOriginal(fullText, normalizedFull, lcsIdx);
                int origEnd = mapNormalizedOffsetToOriginal(fullText, normalizedFull, lcsIdx + lcs.length());
                return new EvidenceSpan(fullText.substring(origStart, Math.min(origEnd, fullText.length())), origStart, origEnd);
            }
        }

        return null;
    }

    /** Normalize text for matching: lowercase, collapse whitespace, normalize quotes/dashes */
    static String normalizeForMatching(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                .replace('\u2018', '\'').replace('\u2019', '\'') // smart single quotes
                .replace('\u201C', '"').replace('\u201D', '"')   // smart double quotes
                .replace('\u2013', '-').replace('\u2014', '-')   // en/em dashes
                .replaceAll("\\s+", " ")                         // collapse whitespace
                .trim();
    }

    /** Type check value based on field name patterns */
    private boolean typeCheck(String canonicalField, String value) {
        if (canonicalField == null || value == null || value.isBlank()) return false;

        String field = canonicalField.toLowerCase();

        // Date fields
        if (field.contains("date") || field.contains("expiry") || field.contains("effective")) {
            return value.matches(".*\\d{4}.*") || value.matches(".*\\d{1,2}[/\\-.]\\d{1,2}.*");
        }
        // Amount fields
        if (field.contains("value") || field.contains("fee") || field.contains("cap") ||
                field.contains("salary") || field.contains("amount") || field.contains("cost") ||
                field.contains("price") || field.contains("payment")) {
            return value.matches(".*[\\d$€£¥₹].*");
        }
        // Percentage fields
        if (field.contains("sla") || field.contains("uptime") || field.contains("percentage") ||
                field.contains("escalation") || field.contains("interest")) {
            return value.matches(".*\\d.*");
        }
        // Period/duration fields
        if (field.contains("period") || field.contains("term") || field.contains("duration") ||
                field.contains("notice")) {
            return value.matches(".*\\d.*") || value.toLowerCase().matches(".*(day|month|year|week).*");
        }

        return true; // Text fields — no type check
    }

    private int mapNormalizedOffsetToOriginal(String original, String normalized, int normalizedOffset) {
        // Simple approximation — works well for whitespace normalization
        int origIdx = 0, normIdx = 0;
        while (normIdx < normalizedOffset && origIdx < original.length()) {
            char origChar = original.charAt(origIdx);
            if (Character.isWhitespace(origChar)) {
                while (origIdx < original.length() && Character.isWhitespace(original.charAt(origIdx))) origIdx++;
                normIdx++; // Collapsed whitespace = 1 space in normalized
            } else {
                origIdx++;
                normIdx++;
            }
        }
        return Math.min(origIdx, original.length());
    }

    private List<Integer> findAllOccurrences(String text, String pattern) {
        List<Integer> positions = new ArrayList<>();
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) >= 0) {
            positions.add(idx);
            idx += 1;
        }
        return positions;
    }

    private String longestCommonSubstring(String a, String b, int minLength) {
        if (b.length() < minLength) return null;
        // Sliding window from full length down to minLength
        for (int len = Math.min(b.length(), 200); len >= minLength; len--) {
            for (int start = 0; start + len <= b.length(); start++) {
                String sub = b.substring(start, start + len);
                if (a.contains(sub)) return sub;
            }
        }
        return null;
    }
}
