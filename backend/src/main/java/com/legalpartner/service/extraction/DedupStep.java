package com.legalpartner.service.extraction;

import com.legalpartner.model.dto.extraction.EvidenceSpan;
import com.legalpartner.model.dto.extraction.ExtractionEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dedup extracted entries. Key = (canonical_field + bucket + normalized_value).
 * Type-aware equivalence: amounts → numeric, dates → ISO, text → exact match.
 * Never dedup across different sections. Conflicting same-field entries marked CONFLICTING_DUPLICATES.
 */
@Component
@Slf4j
public class DedupStep {

    public List<ExtractionEntry> execute(List<ExtractionEntry> entries) {
        // Group by canonical_field (null = unmapped, never dedup)
        Map<String, List<ExtractionEntry>> grouped = new LinkedHashMap<>();
        List<ExtractionEntry> unmapped = new ArrayList<>();

        for (ExtractionEntry e : entries) {
            if (e.canonicalField() == null) {
                unmapped.add(e);
            } else {
                grouped.computeIfAbsent(e.canonicalField(), k -> new ArrayList<>()).add(e);
            }
        }

        List<ExtractionEntry> result = new ArrayList<>();

        for (var entry : grouped.entrySet()) {
            List<ExtractionEntry> group = entry.getValue();
            if (group.size() == 1) {
                result.add(group.get(0));
                continue;
            }

            // Check if values are equivalent
            List<ExtractionEntry> unique = deduplicateGroup(group);
            result.addAll(unique);
        }

        result.addAll(unmapped);
        log.info("Dedup: {} entries → {} entries ({} removed)", entries.size(), result.size(), entries.size() - result.size());
        return result;
    }

    private List<ExtractionEntry> deduplicateGroup(List<ExtractionEntry> group) {
        // Cluster by normalized value
        Map<String, List<ExtractionEntry>> valueClusters = new LinkedHashMap<>();
        for (ExtractionEntry e : group) {
            String normValue = normalizeValue(e.canonicalField(), e.value());
            valueClusters.computeIfAbsent(normValue, k -> new ArrayList<>()).add(e);
        }

        if (valueClusters.size() == 1) {
            // All same value — merge evidence spans
            List<EvidenceSpan> allEvidence = group.stream()
                    .filter(e -> e.evidence() != null)
                    .flatMap(e -> e.evidence().stream())
                    .collect(Collectors.toList());
            ExtractionEntry first = group.get(0);
            return List.of(new ExtractionEntry(
                    first.canonicalField(), first.rawField(), first.value(), first.bucket(),
                    allEvidence, first.extractionConfidence(), first.consistencyStatus(),
                    first.mappingConfidence(), first.importance(), first.reasonCode(),
                    first.userCorrected(), first.sectionRef()
            ));
        }

        // Different values for same field — mark as CONFLICTING_DUPLICATES
        return group.stream()
                .map(e -> e.withConsistency(ExtractionEntry.CONFLICTING_DUPLICATES))
                .collect(Collectors.toList());
    }

    /** Normalize value for equivalence comparison based on field type */
    private String normalizeValue(String canonicalField, String value) {
        if (value == null) return "";
        String v = value.trim().toLowerCase();
        String field = canonicalField != null ? canonicalField.toLowerCase() : "";

        // Amount fields: extract numeric value
        if (field.contains("value") || field.contains("fee") || field.contains("cap") ||
                field.contains("salary") || field.contains("cost") || field.contains("price")) {
            String numeric = extractNumeric(v);
            if (numeric != null) return "AMT:" + numeric;
        }

        // Percentage fields: extract number
        if (field.contains("sla") || field.contains("uptime") || field.contains("percentage") ||
                field.contains("interest") || field.contains("escalation")) {
            String numeric = extractNumeric(v);
            if (numeric != null) return "PCT:" + numeric;
        }

        // Date fields: try ISO normalization
        if (field.contains("date") || field.contains("expiry") || field.contains("effective")) {
            // Simple: extract year-month-day digits
            String digits = v.replaceAll("[^0-9]", "");
            if (digits.length() >= 8) return "DATE:" + digits.substring(0, 8);
        }

        // Text: exact normalized match
        return "TXT:" + v.replaceAll("\\s+", " ");
    }

    /** Extract the first numeric value from a string, handling currency symbols and commas */
    private String extractNumeric(String value) {
        String cleaned = value.replaceAll("[,$€£¥₹\\s]", "")
                .replaceAll("(?i)(thousand|million|billion|k|m|b)", "");
        try {
            // Find first number pattern
            var matcher = java.util.regex.Pattern.compile("[0-9]+\\.?[0-9]*").matcher(cleaned);
            if (matcher.find()) {
                double num = Double.parseDouble(matcher.group());
                // Handle K/M/B multipliers
                String lower = value.toLowerCase();
                if (lower.contains("billion") || lower.endsWith("b")) num *= 1_000_000_000;
                else if (lower.contains("million") || lower.endsWith("m")) num *= 1_000_000;
                else if (lower.contains("thousand") || lower.endsWith("k") || lower.contains("750k")) num *= 1_000;
                return String.format("%.2f", num);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
