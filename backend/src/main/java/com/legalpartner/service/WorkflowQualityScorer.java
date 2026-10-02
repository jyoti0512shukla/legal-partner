package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.enums.WorkflowStepType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * Heuristic (no-LLM) quality scorer for workflow step outputs.
 * Returns a 0–100 score and a list of gaps; passing threshold is
 * legalpartner.workflows.quality.passing-score (default 70).
 *
 * The rubric per step type — checks, points and gap wording — is in
 * {@code config/workflow_quality.yml}; this class only evaluates the check kinds.
 */
@Component
@Slf4j
public class WorkflowQualityScorer {

    private static final String CONFIG_PATH = "config/workflow_quality.yml";

    @Value("${legalpartner.workflows.quality.passing-score:70}")
    private int passingScore = 70;

    public record QualityScore(int score, List<String> gaps) {}

    /** One rubric check; unused fields are null / empty for a given kind. {@code exclude} also holds {@code empty_values}. */
    record Check(String kind, String path, List<String> paths, String field, List<String> fields,
                 List<String> exclude, List<Pattern> patterns, String value, int min, int points, int perItem,
                 String gap, String gapWhenEmpty) {}

    record Rubric(int base, List<Check> checks) {}

    private Map<WorkflowStepType, Rubric> rubrics = Map.of();
    private int onErrorScore = 75;

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) throw new IllegalStateException("Missing " + CONFIG_PATH);
            Map<String, Object> root = new Yaml().load(in);
            this.onErrorScore = root.get("on_error_score") instanceof Number n ? n.intValue() : 75;
            Map<WorkflowStepType, Rubric> out = new EnumMap<>(WorkflowStepType.class);
            ((Map<String, Object>) root.getOrDefault("steps", Map.of())).forEach((type, v) -> {
                Map<String, Object> r = (Map<String, Object>) v;
                List<Check> checks = new ArrayList<>();
                for (Map<String, Object> c : (List<Map<String, Object>>) r.getOrDefault("checks", List.of())) {
                    checks.add(check(c));
                }
                out.put(WorkflowStepType.valueOf(type), new Rubric(((Number) r.get("base")).intValue(), List.copyOf(checks)));
            });
            this.rubrics = Collections.unmodifiableMap(out);
            log.info("WorkflowQualityScorer: rubrics for {} step types", rubrics.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    private static Check check(Map<String, Object> c) {
        String kind = String.valueOf(c.get("kind"));
        if (!KINDS.contains(kind)) throw new IllegalStateException(CONFIG_PATH + ": unknown check kind " + kind);
        return new Check(kind, str(c.get("path")), strings(c.get("paths")), str(c.get("field")),
                strings(c.get("fields")),
                java.util.stream.Stream.concat(strings(c.get("exclude")).stream(), strings(c.get("empty_values")).stream()).toList(),
                strings(c.get("patterns")).stream().map(Pattern::compile).toList(),
                str(c.get("value")), num(c.get("min")), num(c.get("points")), num(c.get("per_item")),
                str(c.get("gap")), str(c.get("gap_when_empty")));
    }

    static final List<String> KINDS = List.of("min_items", "each_item_min_length", "any_item_has", "min_length",
            "not_blank", "equals", "min_number", "fields_present", "no_match", "min_sentences");

    public boolean isPassing(QualityScore q) { return q.score() >= passingScore; }

    /** Step types with a rubric (for config tests). */
    public java.util.Set<WorkflowStepType> ruledTypes() { return rubrics.keySet(); }

    public QualityScore score(WorkflowStepType type, Object result, ObjectMapper mapper) {
        Rubric rubric = rubrics.get(type);
        if (rubric == null) {
            log.warn("No quality rubric for step type {} — treating as passing", type);
            return new QualityScore(100, List.of());
        }
        try {
            JsonNode node = mapper.readTree(mapper.writeValueAsString(result));
            int score = rubric.base();
            List<String> gaps = new ArrayList<>();
            for (Check c : rubric.checks()) score += apply(c, node, gaps);
            return new QualityScore(Math.min(score, 100), gaps);
        } catch (Exception e) {
            log.warn("Quality scoring failed for {}: {}", type, e.getMessage());
            return new QualityScore(onErrorScore, List.of());
        }
    }

    /** Points earned by one check; adds its gap when it fails. */
    static int apply(Check c, JsonNode node, List<String> gaps) {
        switch (c.kind()) {
            case "min_items" -> {
                int count = node.path(c.path()).size();
                if (count >= c.min()) return c.points();
                String gap = count == 0 && c.gapWhenEmpty() != null ? c.gapWhenEmpty() : c.gap();
                addGap(gaps, gap, "{count}", String.valueOf(count));
                return count == 0 && c.gapWhenEmpty() != null ? 0 : count * c.perItem();
            }
            case "each_item_min_length" -> {
                boolean ok = StreamSupport.stream(node.path(c.path()).spliterator(), false)
                        .allMatch(i -> i.path(c.field()).asText("").length() >= c.min());
                return pass(ok, c, gaps);
            }
            case "any_item_has" -> {
                boolean ok = StreamSupport.stream(node.path(c.path()).spliterator(), false).anyMatch(i -> {
                    String v = firstNonBlank(i, c.fields());
                    return !v.isBlank() && c.exclude().stream().noneMatch(v::equalsIgnoreCase);
                });
                return pass(ok, c, gaps);
            }
            case "min_length" -> {
                return pass(firstNonBlank(node, c.paths()).length() >= c.min(), c, gaps);
            }
            case "not_blank" -> {
                return pass(!node.path(c.path()).asText("").isBlank(), c, gaps);
            }
            case "equals" -> {
                String actual = node.path(c.path()).asText("");
                if (actual.equals(c.value())) return c.points();
                addGap(gaps, c.gap(), "{actual}", actual);
                return 0;
            }
            case "min_number" -> {
                return pass(node.path(c.path()).asInt(0) >= c.min(), c, gaps);
            }
            case "fields_present" -> {
                int earned = 0;
                List<String> missing = new ArrayList<>();
                for (String f : c.fields()) {
                    String v = node.path(f).asText("");
                    if (!v.isBlank() && c.exclude().stream().noneMatch(v::equalsIgnoreCase)) {
                        earned += c.points();
                    } else {
                        missing.add(f);
                    }
                }
                if (!missing.isEmpty()) addGap(gaps, c.gap(), "{missing}", String.join(", ", missing));
                return earned;
            }
            case "no_match" -> {
                String text = firstNonBlank(node, c.paths());
                return pass(c.patterns().stream().noneMatch(p -> p.matcher(text).matches()), c, gaps);
            }
            case "min_sentences" -> {
                String text = firstNonBlank(node, c.paths());
                return pass(text.contains(".") && text.split("\\.").length >= c.min(), c, gaps);
            }
            default -> throw new IllegalStateException("Unknown check kind " + c.kind());
        }
    }

    private static int pass(boolean ok, Check c, List<String> gaps) {
        if (ok) return c.points();
        addGap(gaps, c.gap(), null, null);
        return 0;
    }

    private static void addGap(List<String> gaps, String gap, String var, String value) {
        if (gap == null) return;
        gaps.add(var == null ? gap : gap.replace(var, value));
    }

    private static String firstNonBlank(JsonNode node, List<String> paths) {
        for (String p : paths) {
            String v = node.path(p).asText("");
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).toList();
    }
}
