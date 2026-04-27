package com.legalpartner.service.extraction;

import com.legalpartner.model.dto.extraction.ExtractionEntry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * Programmatic consistency checks using fixed bucket categories.
 * Buckets loaded from extraction_buckets.yml.
 * No LLM — deterministic, fast, reliable.
 */
@Component
@Slf4j
public class ConsistencyChecker {

    /** canonical_field → bucket name */
    private final Map<String, String> fieldToBucket = new HashMap<>();
    private Set<String> criticalFields = new HashSet<>();

    public record ConsistencyResult(List<ExtractionEntry> entries, List<String> issues) {}

    @PostConstruct
    void loadBuckets() {
        try {
            InputStream is = new ClassPathResource("config/extraction_buckets.yml").getInputStream();
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> buckets = (Map<String, Map<String, Object>>) root.get("buckets");
            if (buckets != null) {
                for (var entry : buckets.entrySet()) {
                    String bucketName = entry.getKey();
                    @SuppressWarnings("unchecked")
                    List<String> fields = (List<String>) entry.getValue().get("fields");
                    if (fields != null) {
                        for (String field : fields) {
                            fieldToBucket.put(field, bucketName);
                        }
                    }
                }
            }

            @SuppressWarnings("unchecked")
            List<String> critical = (List<String>) root.get("critical_fields");
            if (critical != null) criticalFields = new HashSet<>(critical);

            log.info("ConsistencyChecker loaded {} field→bucket mappings, {} critical fields",
                    fieldToBucket.size(), criticalFields.size());
        } catch (Exception e) {
            log.error("Failed to load extraction_buckets.yml: {}", e.getMessage());
        }
    }

    /** Get the bucket for a canonical field */
    public String getBucket(String canonicalField) {
        return canonicalField != null ? fieldToBucket.getOrDefault(canonicalField, "OTHER") : "OTHER";
    }

    /** Check if a field is in the critical list */
    public boolean isCritical(String canonicalField) {
        return canonicalField != null && criticalFields.contains(canonicalField);
    }

    /** Get the set of critical fields */
    public Set<String> getCriticalFields() { return criticalFields; }

    /** Run consistency checks across all entries */
    public ConsistencyResult check(List<ExtractionEntry> entries) {
        List<String> issues = new ArrayList<>();
        Map<String, ExtractionEntry> byField = new LinkedHashMap<>();
        for (ExtractionEntry e : entries) {
            if (e.canonicalField() != null && e.value() != null && e.reasonCode() == null) {
                byField.put(e.canonicalField(), e);
            }
        }

        // Party A ≠ Party B
        ExtractionEntry partyA = byField.get("party_a");
        ExtractionEntry partyB = byField.get("party_b");
        if (partyA != null && partyB != null && partyA.value().equalsIgnoreCase(partyB.value())) {
            issues.add("Party A and Party B appear to be the same entity");
        }

        // Effective date < Expiry date
        ExtractionEntry effDate = byField.get("effective_date");
        ExtractionEntry expDate = byField.get("expiry_date");
        if (effDate != null && expDate != null) {
            try {
                long effYear = extractYear(effDate.value());
                long expYear = extractYear(expDate.value());
                if (effYear > 0 && expYear > 0 && effYear > expYear) {
                    issues.add("Effective date (" + effDate.value() + ") appears to be after expiry date (" + expDate.value() + ")");
                }
            } catch (Exception ignored) {}
        }

        // Liability cap vs contract value
        ExtractionEntry liabCap = byField.get("liability_cap");
        ExtractionEntry contractVal = byField.get("contract_value");
        if (liabCap != null && contractVal != null) {
            Double capNum = extractAmount(liabCap.value());
            Double valNum = extractAmount(contractVal.value());
            if (capNum != null && valNum != null && capNum > valNum * 5) {
                issues.add("Liability cap (" + liabCap.value() + ") exceeds 5x the contract value (" + contractVal.value() + ")");
            }
        }

        // Set consistency status on entries
        Set<String> failedFields = new HashSet<>();
        for (String issue : issues) {
            // Mark related fields
            if (issue.contains("Party")) { failedFields.add("party_a"); failedFields.add("party_b"); }
            if (issue.contains("date")) { failedFields.add("effective_date"); failedFields.add("expiry_date"); }
            if (issue.contains("Liability") || issue.contains("cap")) { failedFields.add("liability_cap"); failedFields.add("contract_value"); }
        }

        List<ExtractionEntry> updated = entries.stream()
                .map(e -> {
                    if (e.consistencyStatus() != null && e.consistencyStatus().equals(ExtractionEntry.CONFLICTING_DUPLICATES)) {
                        return e; // Already flagged by dedup
                    }
                    if (e.canonicalField() != null && failedFields.contains(e.canonicalField())) {
                        return e.withConsistency(ExtractionEntry.FAILED);
                    }
                    if (e.canonicalField() != null && e.reasonCode() == null) {
                        return e.withConsistency(ExtractionEntry.PASSED);
                    }
                    return e.withConsistency(ExtractionEntry.UNCHECKED);
                })
                .toList();

        log.info("Consistency check: {} issues found", issues.size());
        return new ConsistencyResult(updated, issues);
    }

    private long extractYear(String dateStr) {
        var m = java.util.regex.Pattern.compile("(20\\d{2})").matcher(dateStr);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private Double extractAmount(String value) {
        try {
            String cleaned = value.replaceAll("[^0-9.]", "");
            return cleaned.isEmpty() ? null : Double.parseDouble(cleaned);
        } catch (Exception e) { return null; }
    }
}
