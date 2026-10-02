package com.legalpartner.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips non-prose artifacts from raw LLM clause output — chat-template tokens, echoed prompt
 * markers, JSON wrappers, LaTeX, code comments, generation loops, meta-commentary.
 *
 * The cleanup is a pipeline of steps declared in {@code config/output_cleanup.yml}; this class
 * implements the step operations only. Supporting a new model's artifacts is a YAML change.
 */
@Component
@Slf4j
public class LlmOutputSanitizer {

    private static final String CONFIG_PATH = "config/output_cleanup.yml";

    private List<UnaryOperator<String>> steps = List.of();
    private List<Pattern> metaStartsWith = List.of();
    private List<String> metaContains = List.of();
    private Pattern metaShortLine;
    private int metaShortLineMaxChars;
    private List<ArtifactCheck> artifactChecks = List.of();

    record ArtifactCheck(String name, Pattern pattern, String message, List<String> unlessContractTypeContains) {}

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) throw new IllegalStateException("Missing " + CONFIG_PATH);
            Map<String, Object> root = new Yaml().load(in);

            Map<String, Object> meta = (Map<String, Object>) root.getOrDefault("meta_commentary", Map.of());
            this.metaStartsWith = patterns(meta.get("starts_with_pattern"));
            this.metaContains = strings(meta.get("contains")).stream().map(String::toLowerCase).toList();
            Map<String, Object> shortLine = (Map<String, Object>) meta.get("short_line_pattern");
            if (shortLine != null) {
                this.metaShortLine = Pattern.compile(String.valueOf(shortLine.get("pattern")));
                this.metaShortLineMaxChars = ((Number) shortLine.get("max_chars")).intValue();
            }

            List<ArtifactCheck> checks = new ArrayList<>();
            for (Map<String, Object> c : (List<Map<String, Object>>) root.getOrDefault("qa_artifact_checks", List.of())) {
                checks.add(new ArtifactCheck(String.valueOf(c.get("name")), Pattern.compile(String.valueOf(c.get("pattern"))),
                        String.valueOf(c.get("message")),
                        strings(c.get("unless_contract_type_contains")).stream().map(String::toLowerCase).toList()));
            }
            this.artifactChecks = List.copyOf(checks);

            List<UnaryOperator<String>> built = new ArrayList<>();
            for (Map<String, Object> step : (List<Map<String, Object>>) root.getOrDefault("steps", List.of())) {
                built.add(step(step));
            }
            this.steps = List.copyOf(built);
            log.info("LlmOutputSanitizer: {} cleanup steps from {}", steps.size(), CONFIG_PATH);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    /** Runs the configured pipeline. Null or blank input returns "". */
    public String clean(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String t = raw;
        for (UnaryOperator<String> step : steps) t = step.apply(t);
        return t;
    }

    /** True if the line is model meta-commentary rather than legal prose. */
    public boolean isMetaCommentary(String line) {
        String lower = line.toLowerCase().trim();
        if (lower.isEmpty()) return false;
        for (Pattern p : metaStartsWith) if (p.matcher(lower).matches()) return true;
        for (String s : metaContains) if (lower.contains(s)) return true;
        return metaShortLine != null && lower.length() <= metaShortLineMaxChars && metaShortLine.matcher(lower).matches();
    }

    /** Retry warnings for artifacts still present in cleaned clause text (qa_artifact_checks). */
    public List<String> artifactWarnings(String plain, String contractType) {
        List<String> out = new ArrayList<>();
        if (plain == null) return out;
        String type = contractType == null ? "" : contractType.toLowerCase();
        for (ArtifactCheck c : artifactChecks) {
            if (c.unlessContractTypeContains().stream().anyMatch(type::contains)) continue;
            Matcher m = c.pattern().matcher(plain);
            if (m.find()) out.add(c.message().replace("{match}", m.group()));
        }
        return out;
    }

    // ── step operations ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private UnaryOperator<String> step(Map<String, Object> s) {
        String op = String.valueOf(s.get("op"));
        return switch (op) {
            case "truncate_at_literal" -> truncateAtLiteral(strings(s.get("markers")));
            case "truncate_at_regex" -> truncateAtRegex(patterns(s.get("patterns")));
            case "replace" -> {
                List<Map<String, Object>> rules = (List<Map<String, Object>>) s.get("rules");
                List<Pattern> ps = new ArrayList<>();
                List<String> withs = new ArrayList<>();
                for (Map<String, Object> r : rules) {
                    ps.add(Pattern.compile(String.valueOf(r.get("pattern"))));
                    withs.add(String.valueOf(r.getOrDefault("with", "")));
                }
                yield t -> {
                    for (int i = 0; i < ps.size(); i++) t = ps.get(i).matcher(t).replaceAll(withs.get(i));
                    return t;
                };
            }
            case "replace_literal" -> {
                List<Map<String, Object>> rules = (List<Map<String, Object>>) s.get("rules");
                yield t -> {
                    for (Map<String, Object> r : rules) t = t.replace(String.valueOf(r.get("from")), String.valueOf(r.get("to")));
                    return t;
                };
            }
            case "extract_json_prose" -> extractJsonProse(strings(s.get("fields")), num(s.get("min_chars"), 80));
            case "drop_lines" -> dropLines(patterns(s.get("patterns")));
            case "dedupe_repetition" -> t -> truncateOnRepetition(t, num(s.get("min_chars"), 200),
                    num(s.get("min_segment_chars"), 40), num(s.get("fingerprint_chars"), 60));
            case "drop_meta_lines" -> this::dropMetaLines;
            case "trim" -> String::trim;
            default -> throw new IllegalStateException(CONFIG_PATH + ": unknown step op " + op);
        };
    }

    private static UnaryOperator<String> truncateAtLiteral(List<String> markers) {
        return t -> {
            for (String marker : markers) {
                int idx = t.indexOf(marker);
                if (idx > 0) {
                    log.warn("Draft sanitizer: prompt/marker bleed at '{}', truncating", marker.strip());
                    t = t.substring(0, idx);
                }
            }
            return t;
        };
    }

    private static UnaryOperator<String> truncateAtRegex(List<Pattern> patterns) {
        return t -> {
            for (Pattern p : patterns) {
                Matcher m = p.matcher(t);
                if (m.find()) {
                    log.warn("Draft sanitizer: artifact '{}' at offset {}, truncating", m.group().trim(), m.start());
                    t = t.substring(0, m.start());
                }
            }
            return t;
        };
    }

    private static UnaryOperator<String> extractJsonProse(List<String> fields, int minChars) {
        Pattern value = Pattern.compile("\"(?:" + String.join("|", fields.stream().map(Pattern::quote).toList())
                + ")\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        return t -> {
            if (!(t.trim().startsWith("{") && t.trim().contains("\""))) return t;
            Matcher m = value.matcher(t);
            String longest = null;
            while (m.find()) {
                String v = m.group(1).replace("\\n", "\n").replace("\\t", " ").replace("\\\"", "\"");
                if (longest == null || v.length() > longest.length()) longest = v;
            }
            return longest != null && longest.length() > minChars ? longest : t;
        };
    }

    private static UnaryOperator<String> dropLines(List<Pattern> patterns) {
        return t -> {
            StringBuilder out = new StringBuilder();
            for (String line : t.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (patterns.stream().anyMatch(p -> p.matcher(trimmed).matches())) continue;
                out.append(line).append("\n");
            }
            return out.toString();
        };
    }

    private String dropMetaLines(String t) {
        StringBuilder out = new StringBuilder();
        for (String line : t.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) { out.append("\n"); continue; }
            if (isMetaCommentary(trimmed)) {
                log.debug("Draft sanitizer: stripped meta-commentary line: {}", trimmed.substring(0, Math.min(80, trimmed.length())));
                continue;
            }
            out.append(line).append("\n");
        }
        return out.toString().trim();
    }

    /** Cut at the second occurrence of any segment (fingerprinted by its first characters). */
    static String truncateOnRepetition(String text, int minChars, int minSegmentChars, int fingerprintChars) {
        if (text == null || text.length() < minChars) return text;
        Map<String, Integer> seen = new HashMap<>();
        StringBuilder kept = new StringBuilder();
        for (String seg : text.split("(?<=[.!?\\n])")) {
            String norm = seg.trim().toLowerCase().replaceAll("\\s+", " ");
            if (norm.length() < minSegmentChars) {
                kept.append(seg);
                continue;
            }
            String fp = norm.substring(0, Math.min(fingerprintChars, norm.length()));
            if (seen.merge(fp, 1, Integer::sum) >= 2) {
                log.warn("Draft sanitizer: repetition loop detected, truncating at: {}", fp.substring(0, Math.min(50, fp.length())));
                break;
            }
            kept.append(seg);
        }
        return kept.toString();
    }

    // ── yaml helpers ───────────────────────────────────────────────────────────

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).toList();
    }

    private static List<Pattern> patterns(Object o) {
        return strings(o).stream().map(Pattern::compile).toList();
    }

    private static int num(Object o, int dflt) {
        return o instanceof Number n ? n.intValue() : dflt;
    }
}
