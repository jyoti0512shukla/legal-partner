package com.legalpartner.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Flat list of entities the QA layer flags as likely training-corpus leaks
 * when they appear in generated drafts. Sourced from
 * {@code resources/config/denylists.yml}.
 *
 * Editing: drop an entity from the YAML, restart. No code change.
 *
 * Future: extend with per-customer denylists merged at runtime (e.g. names
 * of prior clients a firm wants aggressively blocked). Not yet wired.
 */
@Component
@Slf4j
public class DenylistRegistry {

    private static final String CONFIG_PATH = "config/denylists.yml";

    /** Categorised view — kept for observability / future per-category rules. */
    private Map<String, List<String>> byCategory = Map.of();
    /** Flattened for O(1) checks in hot paths. */
    private List<String> allEntities = List.of();

    @PostConstruct
    void load() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) {
                log.warn("Denylist config {} not found — running with empty denylist", CONFIG_PATH);
                return;
            }
            Map<String, Object> root = yaml.load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> entities = (Map<String, Object>) root.get("memorized_entities");
            if (entities == null) {
                log.warn("denylists.yml has no 'memorized_entities' block — empty denylist");
                return;
            }
            Map<String, List<String>> cat = new LinkedHashMap<>();
            List<String> flat = new ArrayList<>();
            for (Map.Entry<String, Object> e : entities.entrySet()) {
                List<String> values = stringListOrEmpty(e.getValue());
                cat.put(e.getKey(), values);
                flat.addAll(values);
            }
            this.byCategory = Collections.unmodifiableMap(cat);
            this.allEntities = Collections.unmodifiableList(flat);
            log.info("DenylistRegistry loaded {} entities across {} categories",
                    flat.size(), cat.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    /** Flat list of all denylisted entities across all categories. */
    public List<String> all() { return allEntities; }

    /** Category → entities map (e.g., "jurisdictions", "party_names", "monetary_amounts"). */
    public Map<String, List<String>> byCategory() { return byCategory; }

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
