package com.legalpartner.config;

import com.legalpartner.rag.PromptTemplates;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Two-layer prompt resolver.
 *
 * 1. Baseline: every {@code public static final String} on
 *    {@link PromptTemplates}, loaded via reflection at startup. This is the
 *    pristine prompt library that ships with the code.
 *
 * 2. Overrides: any entry in {@code resources/config/clause_prompts.yml}
 *    under the {@code prompts:} key overrides the baseline for that name.
 *    Deployment-time prompt tuning without a code change.
 *
 * Lookup order: override → baseline → empty string (with a warning).
 *
 * Callers use {@link #get(String)} with the prompt id. The same id is what
 * clauses.yml references via {@code system_prompt_id} / {@code user_prompt_id}.
 */
@Component
@Slf4j
public class PromptRepository {

    private static final String CONFIG_PATH = "config/clause_prompts.yml";

    private Map<String, String> baseline = Map.of();
    private Map<String, String> overrides = Map.of();

    @PostConstruct
    void load() {
        this.baseline = loadBaseline();
        this.overrides = loadOverrides();
        log.info("PromptRepository: {} baseline prompts from PromptTemplates, {} YAML overrides",
                baseline.size(), overrides.size());
    }

    /**
     * Resolve a prompt id to its body. Override wins if present; otherwise the
     * Java baseline. Unknown id → empty string (with a logged warning).
     */
    public String get(String id) {
        if (id == null || id.isBlank()) return "";
        String override = overrides.get(id);
        if (override != null) return override;
        String base = baseline.get(id);
        if (base != null) return base;
        log.warn("PromptRepository: unknown prompt id '{}' — returning empty", id);
        return "";
    }

    public boolean contains(String id) {
        return id != null && (overrides.containsKey(id) || baseline.containsKey(id));
    }

    // ── loaders ───────────────────────────────────────────────────────

    private Map<String, String> loadBaseline() {
        Map<String, String> out = new HashMap<>();
        for (Field f : PromptTemplates.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || !Modifier.isFinal(mods)) continue;
            if (f.getType() != String.class) continue;
            try {
                Object v = f.get(null);
                if (v instanceof String s) out.put(f.getName(), s);
            } catch (IllegalAccessException e) {
                log.debug("Skip prompt field {}: {}", f.getName(), e.getMessage());
            }
        }
        return Map.copyOf(out);
    }

    private Map<String, String> loadOverrides() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) return Map.of();
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) return Map.of();
            Object prompts = root.get("prompts");
            if (!(prompts instanceof Map<?, ?> m) || m.isEmpty()) return Map.of();
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                out.put(e.getKey().toString(), e.getValue().toString());
            }
            return Map.copyOf(out);
        } catch (IOException e) {
            log.warn("PromptRepository: failed to read {}: {}", CONFIG_PATH, e.getMessage());
            return Map.of();
        }
    }
}
