package com.legalpartner.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
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
    /** Auto-derived forbidden-heading sets: union of OTHER clauses' titles, computed once at startup. */
    private Map<String, List<String>> derivedForbiddenByKey = Map.of();

    @Autowired
    private PromptRepository promptRepository;

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
            this.derivedForbiddenByKey = buildDerivedForbidden(out);
            log.info("ClauseTypeRegistry loaded {} clause types from {} (derived forbidden headings built)",
                    out.size(), CONFIG_PATH);
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
     * Resolve a prompt id to its body via PromptRepository — which consults
     * clause_prompts.yml (overrides) first, then PromptTemplates (baseline).
     */
    private String resolvePromptConstant(String id) {
        if (id == null || id.isBlank()) return "";
        return promptRepository.get(id);
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

    /**
     * Combined forbidden-headings list for a clause = YAML-configured
     * (specific sub-clause labels like "Termination for Convenience") UNION
     * derived (titles of all OTHER clauses in the registry).
     *
     * The derived half is auto-maintained: add a new clause with title
     * "Service Credits" and every OTHER clause automatically gets "Service
     * Credits" in its forbidden-headings list. No YAML edits needed.
     */
    public List<String> combinedForbiddenHeadings(String key) {
        if (!byKey.containsKey(key)) return List.of();
        List<String> configured = byKey.get(key).forbiddenHeadings();
        List<String> derived = derivedForbiddenByKey.getOrDefault(key, List.of());
        if (configured.isEmpty()) return derived;
        if (derived.isEmpty()) return configured;
        LinkedHashSet<String> merged = new LinkedHashSet<>(configured);
        merged.addAll(derived);
        return List.copyOf(merged);
    }

    /**
     * Build a per-clause map of "article titles from every OTHER clause."
     * Seeing those inside a given clause's body is a strong signal that the
     * model pasted another clause's content wholesale.
     */
    private Map<String, List<String>> buildDerivedForbidden(Map<String, ClauseTypeConfig> all) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String thisKey : all.keySet()) {
            List<String> others = new ArrayList<>();
            for (Map.Entry<String, ClauseTypeConfig> e : all.entrySet()) {
                if (e.getKey().equals(thisKey)) continue;
                String title = e.getValue().title();
                if (title != null && !title.isBlank()) others.add(title);
            }
            out.put(thisKey, Collections.unmodifiableList(others));
        }
        return Collections.unmodifiableMap(out);
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
