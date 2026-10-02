package com.legalpartner.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jurisdiction-specific legal references for LLM prompts.
 *
 * Prompts carry %MARKER% placeholders; this class replaces them with the values for the
 * request's jurisdiction. All legal content lives in {@code config/jurisdictions.yml} —
 * adding a jurisdiction or correcting a statute reference is a YAML change.
 *
 * The server-wide default comes from {@code legalpartner.legal-system} (USA | INDIA),
 * mapped to a jurisdiction by {@code system_defaults}.
 */
@Component
@ConfigurationProperties(prefix = "legalpartner")
@Slf4j
public class LegalSystemConfig {

    private static final String CONFIG_PATH = "config/jurisdictions.yml";
    private static final Pattern MARKER = Pattern.compile("%([A-Z_]+)%");
    private static final Pattern VAR = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private String legalSystem = "INDIA";

    /** A resolved (non-abstract) jurisdiction: how to match it and its final marker values. */
    record Jurisdiction(String id, String family, List<String> matchAny, List<String> matchAll, Map<String, String> markers) {
        boolean matches(String lower) {
            if (!matchAll.isEmpty()) return matchAll.stream().allMatch(lower::contains);
            return matchAny.stream().anyMatch(lower::contains);
        }
    }

    private List<Jurisdiction> ordered = List.of();
    private Map<String, Jurisdiction> byId = Map.of();
    private Map<String, String> systemDefaults = Map.of();
    private String fallbackId = "india";

    @PostConstruct
    void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) throw new IllegalStateException("Missing " + CONFIG_PATH);
            Map<String, Object> root = new Yaml().load(in);
            parse(root);
            log.info("LegalSystemConfig: {} jurisdictions from {}, default system {} → {}",
                    ordered.size(), CONFIG_PATH, legalSystem, defaultJurisdiction().id());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void parse(Map<String, Object> root) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("jurisdictions");
        if (raw == null || raw.isEmpty()) throw new IllegalStateException(CONFIG_PATH + " has no jurisdictions");

        Map<String, Map<String, Object>> rawById = new LinkedHashMap<>();
        for (Map<String, Object> j : raw) rawById.put((String) j.get("id"), j);

        List<Jurisdiction> out = new ArrayList<>();
        Map<String, Jurisdiction> index = new LinkedHashMap<>();
        for (Map<String, Object> j : raw) {
            if (Boolean.TRUE.equals(j.get("abstract"))) continue;
            String id = (String) j.get("id");
            Map<String, String> markers = new LinkedHashMap<>();
            Map<String, String> vars = new LinkedHashMap<>();
            collect(id, rawById, markers, vars, new ArrayList<>());
            Map<String, String> resolved = new LinkedHashMap<>();
            markers.forEach((k, v) -> resolved.put(k, substituteVars(v, vars, id, k)));
            Object family = j.get("family");
            Jurisdiction jur = new Jurisdiction(id, family != null ? family.toString() : rootOf(id, rawById),
                    lowerList(j.get("match")), lowerList(j.get("match_all")),
                    Collections.unmodifiableMap(resolved));
            out.add(jur);
            index.put(id, jur);
        }
        this.ordered = List.copyOf(out);
        this.byId = Collections.unmodifiableMap(index);

        Map<String, Object> defaults = (Map<String, Object>) root.getOrDefault("system_defaults", Map.of());
        Map<String, String> sd = new LinkedHashMap<>();
        defaults.forEach((k, v) -> sd.put(k.toUpperCase(), v.toString()));
        this.systemDefaults = sd;
        this.fallbackId = String.valueOf(root.getOrDefault("fallback", "india"));
        if (!byId.containsKey(fallbackId)) throw new IllegalStateException("Unknown fallback jurisdiction " + fallbackId);
        sd.values().forEach(v -> {
            if (!byId.containsKey(v)) throw new IllegalStateException("Unknown system default jurisdiction " + v);
        });
    }

    private static String rootOf(String id, Map<String, Map<String, Object>> rawById) {
        String cur = id;
        for (int depth = 0; depth < 20; depth++) {
            Object parent = rawById.get(cur).get("extends");
            if (parent == null) return cur;
            cur = parent.toString();
        }
        throw new IllegalStateException("extends chain too deep at " + id);
    }

    /** Parent-first merge of markers and vars along the {@code extends} chain. */
    @SuppressWarnings("unchecked")
    private static void collect(String id, Map<String, Map<String, Object>> rawById,
                                Map<String, String> markers, Map<String, String> vars, List<String> seen) {
        Map<String, Object> j = rawById.get(id);
        if (j == null) throw new IllegalStateException("Unknown jurisdiction in extends: " + id);
        if (seen.contains(id)) throw new IllegalStateException("Cyclic extends: " + seen);
        seen.add(id);
        Object parent = j.get("extends");
        if (parent != null) collect(parent.toString(), rawById, markers, vars, seen);
        ((Map<String, Object>) j.getOrDefault("markers", Map.of())).forEach((k, v) -> markers.put(k, String.valueOf(v)));
        ((Map<String, Object>) j.getOrDefault("vars", Map.of())).forEach((k, v) -> vars.put(k, String.valueOf(v)));
    }

    private static String substituteVars(String value, Map<String, String> vars, String id, String marker) {
        Matcher m = VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            if (v == null) throw new IllegalStateException(id + "." + marker + ": undefined var {{" + m.group(1) + "}}");
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<String> lowerList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(x -> x.toString().toLowerCase()).toList();
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public String getLegalSystem() { return legalSystem; }

    /** Bound from legalpartner.legal-system (the only property this bean binds). */
    public void setLegalSystem(String legalSystem) { this.legalSystem = legalSystem; }

    public boolean isUSA() { return "USA".equalsIgnoreCase(legalSystem); }

    /** Server-level country name (for the /legal-system endpoint). */
    public String country() { return defaultJurisdiction().markers().get("COUNTRY"); }

    public String contractAct() { return defaultJurisdiction().markers().get("CONTRACT_ACT"); }

    public String arbitrationAct() { return defaultJurisdiction().markers().get("ARBITRATION_ACT"); }

    /**
     * Replace all %MARKER% placeholders using the server-level legal system.
     * Call this on prompts that are NOT per-request (e.g. risk/review analysis).
     */
    public String localize(String prompt) {
        return localizeForJurisdiction(prompt, null);
    }

    /**
     * Replace all %MARKER% placeholders using a per-request jurisdiction string
     * (e.g. "California, United States", "England and Wales", "India (Maharashtra)").
     * Unknown markers are left untouched.
     */
    public String localizeForJurisdiction(String prompt, String jurisdiction) {
        if (prompt == null) return null;
        Map<String, String> markers = resolve(jurisdiction).markers();
        Matcher m = MARKER.matcher(prompt);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = markers.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Jurisdiction id a jurisdiction string resolves to (for logging and tests). */
    public String resolveId(String jurisdiction) {
        return resolve(jurisdiction).id();
    }

    /**
     * Precedent family of a jurisdiction string (e.g. "Delaware" → "us"), or empty when no
     * configured jurisdiction matches — unlike {@link #resolveId}, never the fallback.
     */
    public java.util.Optional<String> family(String jurisdiction) {
        if (jurisdiction == null || jurisdiction.isBlank()) return java.util.Optional.empty();
        String lower = jurisdiction.toLowerCase();
        return ordered.stream().filter(j -> j.matches(lower)).findFirst().map(Jurisdiction::family);
    }

    Jurisdiction resolve(String jurisdiction) {
        if (jurisdiction == null || jurisdiction.isBlank()) return defaultJurisdiction();
        String lower = jurisdiction.toLowerCase();
        for (Jurisdiction j : ordered) {
            if (j.matches(lower)) return j;
        }
        return byId.get(fallbackId);
    }

    private Jurisdiction defaultJurisdiction() {
        String id = systemDefaults.get(legalSystem == null ? "" : legalSystem.toUpperCase());
        return byId.get(id != null ? id : fallbackId);
    }

    /** All resolved jurisdictions, in match order (for config tests). */
    List<Jurisdiction> jurisdictions() { return ordered; }
}
