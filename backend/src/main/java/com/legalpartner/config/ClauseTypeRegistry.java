package com.legalpartner.config;

import com.legalpartner.rag.PromptTemplates;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Single source of truth for per-clause-type configuration.
 *
 * Replaces three separate hardcoded maps (CLAUSE_SPECS, CLAUSE_QUERIES,
 * CLAUSE_SEMANTIC_REQUIREMENTS) plus per-clause switches (acceptableClauseTypes,
 * forbidden_headings) with a single YAML file at
 * {@code resources/config/clauses.yml}.
 *
 * Adding a new clause type: one YAML block. Updating queries / forbidden
 * terms / expected subclause count: edit YAML, restart. No Java changes,
 * no scattering updates across 5+ files.
 *
 * Prompt bodies are still Java constants in {@link PromptTemplates}
 * — the YAML references them by name (e.g. {@code DRAFT_LIABILITY_SYSTEM}).
 * Phase 2 will move the bodies to text files in resources/prompts/.
 */
@Component
@Slf4j
public class ClauseTypeRegistry {

    private static final String CONFIG_PATH = "config/clauses.yml";

    /** Immutable config object for one clause type. */
    public record ClauseTypeConfig(
            String key,
            String title,
            int expectedSubclauses,
            String systemPrompt,
            String userPromptTemplate,
            List<String> searchQueries,
            Set<String> acceptableClauseTypes,
            List<String> semanticRequirements,
            List<String> forbiddenHeadings
    ) {}

    private Map<String, ClauseTypeConfig> byKey = Map.of();

    @PostConstruct
    void load() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Missing clause config: " + CONFIG_PATH);
            }
            Map<String, Object> root = yaml.load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> clauses = (Map<String, Object>) root.get("clauses");
            if (clauses == null || clauses.isEmpty()) {
                throw new IllegalStateException("clauses.yml has no 'clauses' block");
            }
            Map<String, ClauseTypeConfig> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : clauses.entrySet()) {
                String key = entry.getKey();
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) entry.getValue();
                out.put(key, build(key, raw));
            }
            this.byKey = Collections.unmodifiableMap(out);
            log.info("ClauseTypeRegistry loaded {} clause types from {}", out.size(), CONFIG_PATH);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    private ClauseTypeConfig build(String key, Map<String, Object> raw) {
        return new ClauseTypeConfig(
                key,
                stringOrDefault(raw.get("title"), key),
                intOrDefault(raw.get("expected_subclauses"), 0),
                resolvePromptConstant(stringOrDefault(raw.get("system_prompt_id"), null)),
                resolvePromptConstant(stringOrDefault(raw.get("user_prompt_id"), null)),
                stringListOrEmpty(raw.get("search_queries")),
                stringListOrEmpty(raw.get("acceptable_clause_types")).stream()
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                stringListOrEmpty(raw.get("semantic_requirements")),
                stringListOrEmpty(raw.get("forbidden_headings"))
        );
    }

    /**
     * Resolve a prompt id (e.g. "DRAFT_LIABILITY_SYSTEM") to the actual
     * String constant in PromptTemplates via reflection. Kept out of the
     * hot path (called once at startup per clause).
     */
    private String resolvePromptConstant(String id) {
        if (id == null || id.isBlank()) return "";
        try {
            Field f = PromptTemplates.class.getDeclaredField(id);
            Object v = f.get(null);
            return v != null ? v.toString() : "";
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("Prompt constant {} not found in PromptTemplates — clause will run without its template", id);
            return "";
        }
    }

    // ── Public lookups ────────────────────────────────────────────────

    public ClauseTypeConfig get(String key) {
        ClauseTypeConfig c = byKey.get(key);
        if (c == null) throw new IllegalArgumentException("Unknown clause type: " + key);
        return c;
    }

    public boolean contains(String key) {
        return byKey.containsKey(key);
    }

    public Set<String> allKeys() {
        return byKey.keySet();
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static String stringOrDefault(Object v, String d) {
        return (v instanceof String s && !s.isBlank()) ? s : d;
    }

    private static int intOrDefault(Object v, int d) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListOrEmpty(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object x : list) if (x != null) out.add(x.toString());
            return Collections.unmodifiableList(out);
        }
        return List.of();
    }
}
