package com.legalpartner.service.extraction;

import com.legalpartner.model.entity.AliasOverride;
import com.legalpartner.repository.AliasOverrideRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps raw LLM field names to canonical IDs.
 * Sources: static YAML aliases + DB overrides (self-improving from user corrections).
 * Match priority: exact → alias → fuzzy (Levenshtein ≤ 2).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CanonicalMapper {

    private final AliasOverrideRepository overrideRepo;

    /** canonical_id → set of aliases (lowercase) */
    private Map<String, Set<String>> aliasMap = new ConcurrentHashMap<>();
    /** alias (lowercase) → canonical_id (reverse lookup) */
    private Map<String, String> reverseLookup = new ConcurrentHashMap<>();
    /** Set of all known canonical IDs */
    private Set<String> canonicalIds = new HashSet<>();

    public record MappingResult(String canonicalField, String rawField, String confidence) {}

    @PostConstruct
    void init() {
        loadYamlAliases();
        loadDbOverrides();
    }

    @Scheduled(fixedDelay = 300_000) // Refresh DB overrides every 5 minutes
    void refreshOverrides() {
        loadDbOverrides();
    }

    private void loadYamlAliases() {
        try {
            InputStream is = new ClassPathResource("config/canonical_aliases.yml").getInputStream();
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            @SuppressWarnings("unchecked")
            Map<String, List<String>> aliases = (Map<String, List<String>>) root.get("aliases");
            if (aliases == null) return;

            for (var entry : aliases.entrySet()) {
                String canonical = entry.getKey();
                canonicalIds.add(canonical);
                Set<String> aliasSet = aliasMap.computeIfAbsent(canonical, k -> ConcurrentHashMap.newKeySet());
                for (String alias : entry.getValue()) {
                    String normalized = normalize(alias);
                    aliasSet.add(normalized);
                    reverseLookup.put(normalized, canonical);
                }
                // Also add the canonical ID itself as a self-alias
                reverseLookup.put(canonical, canonical);
            }
            log.info("CanonicalMapper loaded {} canonical fields with {} total aliases from YAML",
                    canonicalIds.size(), reverseLookup.size());
        } catch (Exception e) {
            log.error("Failed to load canonical_aliases.yml: {}", e.getMessage());
        }
    }

    private void loadDbOverrides() {
        try {
            List<AliasOverride> overrides = overrideRepo.findAll();
            for (AliasOverride o : overrides) {
                String normalized = normalize(o.getRawField());
                reverseLookup.put(normalized, o.getCanonicalField());
                canonicalIds.add(o.getCanonicalField());
            }
            if (!overrides.isEmpty()) {
                log.info("CanonicalMapper loaded {} DB alias overrides", overrides.size());
            }
        } catch (Exception e) {
            log.debug("Could not load DB alias overrides: {}", e.getMessage());
        }
    }

    /**
     * Map a raw field name to a canonical ID.
     * Returns the mapping with confidence: HIGH (exact/alias), MEDIUM (fuzzy), LOW (no match).
     */
    public MappingResult map(String rawFieldName) {
        if (rawFieldName == null || rawFieldName.isBlank()) {
            return new MappingResult(null, rawFieldName, "LOW");
        }

        String normalized = normalize(rawFieldName);

        // 1. Exact match against canonical IDs
        if (canonicalIds.contains(normalized)) {
            return new MappingResult(normalized, rawFieldName, "HIGH");
        }

        // 2. Alias match (reverse lookup)
        String mapped = reverseLookup.get(normalized);
        if (mapped != null) {
            return new MappingResult(mapped, rawFieldName, "HIGH");
        }

        // 3. Fuzzy match — Levenshtein distance ≤ 2 against all aliases
        for (var entry : reverseLookup.entrySet()) {
            if (levenshtein(normalized, entry.getKey()) <= 2) {
                return new MappingResult(entry.getValue(), rawFieldName, "MEDIUM");
            }
        }

        // 4. Substring match — check if any alias is contained in the raw field
        for (var entry : reverseLookup.entrySet()) {
            if (entry.getKey().length() >= 5 && normalized.contains(entry.getKey())) {
                return new MappingResult(entry.getValue(), rawFieldName, "MEDIUM");
            }
        }

        // 5. No match — keep raw field, LOW confidence
        return new MappingResult(null, rawFieldName, "LOW");
    }

    /** Resolve bucket for a canonical field. Returns null if not found. */
    public String getBucketForField(String canonicalField) {
        // Delegated to ConsistencyChecker which owns the bucket config
        return null;
    }

    private String normalize(String input) {
        return input.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", "_")
                .trim();
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
