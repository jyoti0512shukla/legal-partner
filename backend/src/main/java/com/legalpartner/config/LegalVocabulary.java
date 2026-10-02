package com.legalpartner.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Legal vocabulary for retrieval and classification, from {@code config/vocabulary.yml}.
 * All maps preserve YAML order (it matters for tie-breaking).
 */
@Component
@Slf4j
public class LegalVocabulary {

    private static final String CONFIG_PATH = "config/vocabulary.yml";

    public record TypeSignals(String type, List<String> strong, List<String> weak) {}

    public record ClauseHint(String type, List<String> ifPresent, List<String> ifAbsent) {
        public boolean matches(Set<String> presentClauses) {
            return presentClauses.containsAll(ifPresent) && ifAbsent.stream().noneMatch(presentClauses::contains);
        }
    }

    public record RiskCategory(String key, String label, List<String> stems, List<String> sectionKeywords) {}

    /** A concrete, deal-specific value pattern (amount, date, email, phone …). */
    public record EntityPattern(String name, String placeholder, java.util.regex.Pattern pattern, int minDigits) {
        /** True if the match is specific enough to count as a leak (enough digits). */
        public boolean specific(String match) {
            return minDigits <= 0 || match.replaceAll("\\D", "").length() >= minDigits;
        }
    }

    private Map<String, List<String>> stemSynonyms = Map.of();
    private Map<String, String> acronymExpansions = Map.of();
    private Set<String> rerankAcronyms = Set.of();
    private Map<String, Double> doctypeAuthority = Map.of();
    private Map<String, List<String>> chunkClauseKeywords = Map.of();
    private List<TypeSignals> typeSignals = List.of();
    private double typeMinConfidence = 0.15;
    private List<ClauseHint> clauseHints = List.of();
    private Map<String, RiskCategory> riskCategories = Map.of();
    private List<String> highRiskPhrases = List.of();
    private List<String> lowRiskPhrases = List.of();
    private List<String> documentContextQueries = List.of();
    private List<EntityPattern> entityPatterns = List.of();

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) throw new IllegalStateException("Missing " + CONFIG_PATH);
            Map<String, Object> root = new Yaml().load(in);

            Map<String, Object> qe = map(root.get("query_expansion"));
            this.stemSynonyms = listMap(qe.get("stem_synonyms"));
            Map<String, String> acr = new LinkedHashMap<>();
            map(qe.get("acronyms")).forEach((k, v) -> acr.put(k.toLowerCase(), String.valueOf(v)));
            this.acronymExpansions = Collections.unmodifiableMap(acr);

            Map<String, Object> rr = map(root.get("rerank"));
            this.rerankAcronyms = Collections.unmodifiableSet(new LinkedHashSet<>(strings(rr.get("legal_acronyms"))));
            Map<String, Double> auth = new LinkedHashMap<>();
            map(rr.get("doctype_authority")).forEach((k, v) -> auth.put(k, ((Number) v).doubleValue()));
            this.doctypeAuthority = Collections.unmodifiableMap(auth);

            this.chunkClauseKeywords = listMap(root.get("chunk_clause_keywords"));

            Map<String, Object> det = map(root.get("contract_type_detection"));
            this.typeMinConfidence = det.get("min_confidence") instanceof Number n ? n.doubleValue() : 0.15;
            List<TypeSignals> types = new ArrayList<>();
            for (Object o : (List<Object>) det.getOrDefault("types", List.of())) {
                Map<String, Object> t = map(o);
                types.add(new TypeSignals(String.valueOf(t.get("type")), lower(strings(t.get("strong"))), lower(strings(t.get("weak")))));
            }
            this.typeSignals = List.copyOf(types);
            List<ClauseHint> hints = new ArrayList<>();
            for (Object o : (List<Object>) det.getOrDefault("clause_hints", List.of())) {
                Map<String, Object> h = map(o);
                hints.add(new ClauseHint(String.valueOf(h.get("type")), strings(h.get("if_present")), strings(h.get("if_absent"))));
            }
            this.clauseHints = List.copyOf(hints);

            Map<String, RiskCategory> cats = new LinkedHashMap<>();
            map(root.get("risk_categories")).forEach((k, v) -> {
                Map<String, Object> c = map(v);
                cats.put(k, new RiskCategory(k, String.valueOf(c.get("label")),
                        lower(strings(c.get("stems"))), lower(strings(c.get("section_keywords")))));
            });
            this.riskCategories = Collections.unmodifiableMap(cats);

            Map<String, Object> phrases = map(root.get("risk_phrases"));
            this.highRiskPhrases = lower(strings(phrases.get("high")));
            this.lowRiskPhrases = lower(strings(phrases.get("low")));
            this.documentContextQueries = strings(root.get("document_context_queries"));
            List<EntityPattern> ents = new ArrayList<>();
            for (Object o : (List<Object>) root.getOrDefault("entity_patterns", List.of())) {
                Map<String, Object> e = map(o);
                ents.add(new EntityPattern(String.valueOf(e.get("name")), String.valueOf(e.get("placeholder")),
                        java.util.regex.Pattern.compile(String.valueOf(e.get("pattern"))),
                        e.get("min_digits") instanceof Number n ? n.intValue() : 0));
            }
            this.entityPatterns = List.copyOf(ents);

            if (typeSignals.isEmpty() || chunkClauseKeywords.isEmpty() || riskCategories.isEmpty() || entityPatterns.isEmpty()) {
                throw new IllegalStateException(CONFIG_PATH + " is missing required sections");
            }
            log.info("LegalVocabulary: {} synonym stems, {} contract types, {} risk categories",
                    stemSynonyms.size(), typeSignals.size(), riskCategories.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    public Map<String, List<String>> stemSynonyms() { return stemSynonyms; }
    public Map<String, String> acronymExpansions() { return acronymExpansions; }
    public Set<String> rerankAcronyms() { return rerankAcronyms; }
    public Map<String, Double> doctypeAuthority() { return doctypeAuthority; }
    public Map<String, List<String>> chunkClauseKeywords() { return chunkClauseKeywords; }
    public List<TypeSignals> typeSignals() { return typeSignals; }
    public double typeMinConfidence() { return typeMinConfidence; }
    public List<ClauseHint> clauseHints() { return clauseHints; }
    public Map<String, RiskCategory> riskCategories() { return riskCategories; }
    public List<String> highRiskPhrases() { return highRiskPhrases; }
    public List<String> lowRiskPhrases() { return lowRiskPhrases; }
    public List<String> documentContextQueries() { return documentContextQueries; }
    public List<EntityPattern> entityPatterns() { return entityPatterns; }

    /** Risk category by display label (case-insensitive), e.g. "IP Rights". */
    public RiskCategory riskCategoryByLabel(String label) {
        if (label == null) return null;
        return riskCategories.values().stream().filter(c -> c.label().equalsIgnoreCase(label)).findFirst().orElse(null);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).toList();
    }

    private static List<String> lower(List<String> l) {
        return l.stream().map(String::toLowerCase).toList();
    }

    private static Map<String, List<String>> listMap(Object o) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        map(o).forEach((k, v) -> out.put(k, strings(v)));
        return Collections.unmodifiableMap(out);
    }
}
