package com.legalpartner.service.learning;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.enums.DocumentType;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.service.learning.LearningConfig.NormRule;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Firm norms — deal-point benchmarking within the firm: what this firm actually agrees to,
 * from its signed contracts (extracted terms on {@link DocumentMetadata}).
 *
 * Which terms are benchmarked, how far a contract may depart, and the wording of each note
 * are rules in {@code learning.yml norms.rules}; each rule names a DocumentMetadata property.
 * Adding a benchmarked term is a YAML change (plus the extractor populating the property).
 */
@Service
@RequiredArgsConstructor
public class FirmNormsService {

    private final DocumentMetadataRepository documents;
    private final LearningConfig config;

    /**
     * The firm's norm for one term. Numeric: median / p25 / p75 over {@code n} values.
     * Categorical: most common {@code value} and its {@code share} of {@code n} values.
     */
    public record Norm(String field, String label, String kind, int n,
                       Integer median, Integer p25, Integer p75, String value, double share) {}

    public record Norms(String documentType, int signedContracts, Map<String, Norm> byField) {}

    public Norms compute(DocumentType type) {
        List<DocumentMetadata> signed = type == null ? List.of()
                : documents.findByDocumentTypeAndContractStatusIn(type, config.getSignedStatuses());
        return compute(type == null ? null : type.name(), signed, config.getNormRules());
    }

    static Norms compute(String type, List<DocumentMetadata> signed, List<NormRule> rules) {
        Map<String, Norm> out = new LinkedHashMap<>();
        for (NormRule r : rules) {
            List<Object> values = signed.stream().map(d -> read(d, r.field())).filter(FirmNormsService::present).toList();
            out.put(r.field(), r.numeric() ? numeric(r, values) : categorical(r, values));
        }
        return new Norms(type, signed.size(), Collections.unmodifiableMap(out));
    }

    static Norm numeric(NormRule r, List<Object> values) {
        List<Integer> sorted = values.stream().map(v -> ((Number) v).intValue()).filter(v -> v > 0).sorted().toList();
        if (sorted.isEmpty()) return new Norm(r.field(), r.label(), r.kind(), 0, null, null, null, null, 0);
        return new Norm(r.field(), r.label(), r.kind(), sorted.size(),
                percentile(sorted, 50), percentile(sorted, 25), percentile(sorted, 75), null, 0);
    }

    static Integer percentile(List<Integer> sorted, int p) {
        int idx = (int) Math.round((p / 100.0) * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    static Norm categorical(NormRule r, List<Object> raw) {
        List<String> values = raw.stream().map(v -> normalize(String.valueOf(v))).toList();
        if (values.isEmpty()) return new Norm(r.field(), r.label(), r.kind(), 0, null, null, null, null, 0);
        Map<String, Long> counts = values.stream().collect(Collectors.groupingBy(String::toLowerCase, Collectors.counting()));
        var top = Collections.max(counts.entrySet(), Map.Entry.comparingByValue());
        String display = values.stream().filter(v -> v.equalsIgnoreCase(top.getKey())).findFirst().orElse(top.getKey());
        return new Norm(r.field(), r.label(), r.kind(), values.size(), null, null, null, display,
                (double) top.getValue() / values.size());
    }

    /**
     * Fills empty DealSpec properties named by numeric rules' {@code drafting_default} with the
     * firm median (when supported by enough signed contracts). Returns property → value applied.
     */
    public Map<String, Object> applyDraftingDefaults(Object dealSpec, DocumentType type) {
        Map<String, Object> applied = new LinkedHashMap<>();
        if (!config.isEnabled() || type == null || dealSpec == null) return applied;
        List<NormRule> rules = config.getNormRules().stream()
                .filter(r -> r.numeric() && r.draftingDefault() != null).toList();
        if (rules.isEmpty()) return applied;
        Norms norms = compute(type);
        BeanWrapperImpl bean = new BeanWrapperImpl(dealSpec);
        bean.setAutoGrowNestedPaths(true);
        for (NormRule r : rules) {
            Norm n = norms.byField().get(r.field());
            if (n == null || n.n() < config.getNormsMinSupport() || n.median() == null) continue;
            if (!bean.isWritableProperty(r.draftingDefault())) continue;
            if (present(bean.getPropertyValue(r.draftingDefault()))) continue;
            bean.setPropertyValue(r.draftingDefault(), n.median());
            applied.put(r.draftingDefault(), n.median());
        }
        return applied;
    }

    /** Review notes where a contract departs from the firm's usual terms. */
    public List<String> deviations(DocumentMetadata doc) {
        if (!config.isEnabled() || doc == null || doc.getDocumentType() == null) return List.of();
        return deviations(doc, compute(doc.getDocumentType()), config.getNormRules(), config.getNormsMinSupport());
    }

    static List<String> deviations(DocumentMetadata doc, Norms norms, List<NormRule> rules, int minSupport) {
        List<String> out = new ArrayList<>();
        for (NormRule r : rules) {
            Norm n = norms.byField().get(r.field());
            Object v = read(doc, r.field());
            if (n == null || n.n() < minSupport || !present(v)) continue;
            if (r.numeric()) {
                int value = ((Number) v).intValue();
                if (n.median() == null || n.median() <= 0) continue;
                if (Math.abs(value - n.median()) / (double) n.median() >= r.minRelativeDeviation()) {
                    out.add(message(r, String.valueOf(value), String.valueOf(n.median()), n, norms.documentType()));
                }
            } else {
                String value = normalize(String.valueOf(v));
                if (n.value() != null && n.share() >= r.minShare() && !value.equalsIgnoreCase(n.value())) {
                    out.add(message(r, value, n.value(), n, norms.documentType()));
                }
            }
        }
        return out;
    }

    static String message(NormRule r, String value, String norm, Norm n, String type) {
        return r.message()
                .replace("{value}", value)
                .replace("{norm}", norm)
                .replace("{n}", String.valueOf(n.n()))
                .replace("{share}", String.valueOf(Math.round(n.share() * 100)))
                .replace("{type}", type == null ? "" : type)
                .replace("{label}", r.label());
    }

    /** Reads a DocumentMetadata bean property by name (validated against the entity by config tests). */
    static Object read(DocumentMetadata doc, String property) {
        return new BeanWrapperImpl(doc).getPropertyValue(property);
    }

    private static boolean present(Object v) {
        return v != null && !(v instanceof String s && s.isBlank());
    }

    private static String normalize(String v) {
        return v.trim().replaceAll("\\s+", " ");
    }
}
