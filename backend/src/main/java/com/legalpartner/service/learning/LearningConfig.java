package com.legalpartner.service.learning;

import com.legalpartner.model.enums.ContractStatus;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Learning-loop policy and tuning from {@code config/learning.yml}. Only the ops switch
 * ({@code legalpartner.learning.enabled}) comes from application.yml.
 */
@Component
@Getter
@Slf4j
public class LearningConfig {

    private static final String CONFIG_PATH = "config/learning.yml";

    @Value("${legalpartner.learning.enabled:true}")
    private boolean enabled = true;

    private Set<ContractStatus> signedStatuses = EnumSet.noneOf(ContractStatus.class);
    private Set<String> neverPrecedentSources = Set.of();
    private Set<String> aiGeneratedSources = Set.of();

    private List<String> positions = List.of();
    private Map<String, List<String>> stancePositionOrder = Map.of();

    private int miningMinSupport;
    private double miningSimilarity;
    private int miningMinClauseChars;
    private int miningMaxClauseChars;
    private double clusterMatchOverlap;
    private int minhashNumHashes;
    private int minhashBands;
    private int minhashShingleWords;
    private long minhashSeed;

    private String partyAPlaceholder;
    private String partyBPlaceholder;
    private String otherEntityPlaceholder;
    private List<String> corporateSuffixes = List.of();
    private Set<String> genericNameWords = Set.of();

    private Set<String> firmEditSources = Set.of();
    private double significantEditRatio;
    private double helpfulEditRatio;
    private double harmfulEditRatio;

    private boolean insightAutoActivate;
    private int insightAutoActivateDocs;
    private int insightsPerClause;
    private double insightSimilarity;
    private int insightRetireHarmful;

    private double calibrationPriorCorrect;
    private double calibrationPriorWrong;
    private double calibrationMinMultiplier;
    private int calibrationFewShot;
    private int calibrationContractTypeMinAnswered;
    private int needsRewriteMinAnswered;
    private double needsRewriteMaxPrecision;

    private int normsMinSupport;
    private Set<String> curateRoles = Set.of();
    private Set<String> disputeRoles = Set.of();
    private int metricsWindowDays;
    private List<NormRule> normRules = List.of();

    /** One firm-norm rule (see learning.yml norms.rules). */
    public record NormRule(String field, String kind, String label, double minRelativeDeviation,
                           double minShare, String message, String draftingDefault) {
        public boolean numeric() { return "numeric".equalsIgnoreCase(kind); }
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) throw new IllegalStateException("Missing " + CONFIG_PATH);
            Map<String, Object> root = new Yaml().load(in);

            EnumSet<ContractStatus> signed = EnumSet.noneOf(ContractStatus.class);
            for (String s : strings(root.get("signed_statuses"))) signed.add(ContractStatus.valueOf(s));
            this.signedStatuses = Collections.unmodifiableSet(signed);

            Map<String, Object> sources = map(root.get("sources"));
            this.neverPrecedentSources = upperSet(sources.get("never_precedent"));
            this.aiGeneratedSources = upperSet(sources.get("ai_generated"));

            Map<String, Object> fc = map(root.get("firm_clauses"));
            this.positions = strings(fc.get("positions"));
            if (positions.isEmpty()) throw new IllegalStateException(CONFIG_PATH + ": firm_clauses.positions is empty");
            Map<String, List<String>> order = new LinkedHashMap<>();
            map(fc.get("stance_position_order")).forEach((k, v) -> order.put(k.toUpperCase(), strings(v)));
            this.stancePositionOrder = Collections.unmodifiableMap(order);

            Map<String, Object> mining = map(root.get("mining"));
            this.miningMinSupport = i(mining, "min_support");
            this.miningSimilarity = d(mining, "similarity");
            this.miningMinClauseChars = i(mining, "min_clause_chars");
            this.miningMaxClauseChars = i(mining, "max_clause_chars");
            this.clusterMatchOverlap = d(mining, "cluster_match_overlap");
            Map<String, Object> mh = map(mining.get("minhash"));
            this.minhashNumHashes = i(mh, "num_hashes");
            this.minhashBands = i(mh, "bands");
            this.minhashShingleWords = i(mh, "shingle_words");
            this.minhashSeed = ((Number) mh.get("seed")).longValue();
            if (minhashNumHashes % minhashBands != 0) {
                throw new IllegalStateException(CONFIG_PATH + ": mining.minhash.num_hashes must be divisible by bands");
            }

            Map<String, Object> t = map(root.get("templating"));
            this.partyAPlaceholder = String.valueOf(t.get("party_a_placeholder"));
            this.partyBPlaceholder = String.valueOf(t.get("party_b_placeholder"));
            this.otherEntityPlaceholder = String.valueOf(t.get("other_entity_placeholder"));
            this.corporateSuffixes = strings(t.get("corporate_suffixes"));
            this.genericNameWords = Set.copyOf(strings(t.get("generic_name_words")).stream().map(String::toLowerCase).toList());

            Map<String, Object> edits = map(root.get("edits"));
            this.firmEditSources = upperSet(edits.get("firm_edit_sources"));
            this.significantEditRatio = d(edits, "significant_ratio");
            this.helpfulEditRatio = d(edits, "helpful_ratio");
            this.harmfulEditRatio = d(edits, "harmful_ratio");

            Map<String, Object> ins = map(root.get("insights"));
            this.insightAutoActivate = Boolean.TRUE.equals(ins.get("auto_activate"));
            this.insightAutoActivateDocs = i(ins, "auto_activate_documents");
            this.insightsPerClause = i(ins, "max_per_clause");
            this.insightSimilarity = d(ins, "similarity");
            this.insightRetireHarmful = i(ins, "retire_after_harmful");

            Map<String, Object> cal = map(root.get("calibration"));
            this.calibrationPriorCorrect = d(cal, "prior_correct");
            this.calibrationPriorWrong = d(cal, "prior_wrong");
            this.calibrationMinMultiplier = d(cal, "min_multiplier");
            this.calibrationFewShot = i(cal, "few_shot");
            this.calibrationContractTypeMinAnswered = i(cal, "contract_type_min_answered");
            Map<String, Object> nr = map(cal.get("needs_rewrite"));
            this.needsRewriteMinAnswered = i(nr, "min_answered");
            this.needsRewriteMaxPrecision = d(nr, "max_precision");

            Map<String, Object> norms = map(root.get("norms"));
            this.normsMinSupport = i(norms, "min_support");
            List<NormRule> rules = new ArrayList<>();
            for (Object o : (List<Object>) norms.getOrDefault("rules", List.of())) {
                Map<String, Object> r = map(o);
                rules.add(new NormRule(String.valueOf(r.get("field")), String.valueOf(r.get("kind")),
                        String.valueOf(r.getOrDefault("label", r.get("field"))),
                        r.get("min_relative_deviation") instanceof Number n ? n.doubleValue() : 0.5,
                        r.get("min_share") instanceof Number n ? n.doubleValue() : 0.6,
                        String.valueOf(r.get("message")),
                        r.get("drafting_default") == null ? null : String.valueOf(r.get("drafting_default"))));
            }
            this.normRules = List.copyOf(rules);

            Map<String, Object> access = map(root.get("access"));
            this.curateRoles = upperSet(access.get("curate"));
            this.disputeRoles = upperSet(access.get("dispute"));
            this.metricsWindowDays = i(map(root.get("metrics")), "window_days");
            log.info("LearningConfig: signed={}, positions={}, {} norm rules", signedStatuses, positions, normRules.size());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH + ": " + e.getMessage(), e);
        }
    }

    public boolean isSigned(ContractStatus s) { return s != null && signedStatuses.contains(s); }

    public boolean isAiGenerated(String source) { return source != null && aiGeneratedSources.contains(source.toUpperCase()); }

    public boolean isNeverPrecedent(String source) { return source != null && neverPrecedentSources.contains(source.toUpperCase()); }

    /** Reusable firm language: not an excluded source, and AI output only once signed. */
    public boolean isFirmPrecedent(String source, ContractStatus status) {
        if (isNeverPrecedent(source)) return false;
        return !isAiGenerated(source) || isSigned(status);
    }

    public boolean isFirmEdit(String versionSource) { return versionSource != null && firmEditSources.contains(versionSource.toUpperCase()); }

    public boolean isPosition(String position) { return position != null && positions.contains(position); }

    /** Position preference for a draft stance (falls back to {@code _default}, then the positions list). */
    public List<String> positionOrder(String stance) {
        List<String> o = stance == null ? null : stancePositionOrder.get(stance.toUpperCase());
        if (o == null) o = stancePositionOrder.get("_DEFAULT");
        return o != null ? o : positions;
    }

    public String defaultPosition() { return positions.get(0); }

    // ── yaml helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).toList();
    }

    private static Set<String> upperSet(Object o) {
        return Set.copyOf(strings(o).stream().map(String::toUpperCase).toList());
    }

    private static int i(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) throw new IllegalStateException(CONFIG_PATH + ": missing number " + k);
        return n.intValue();
    }

    private static double d(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) throw new IllegalStateException(CONFIG_PATH + ": missing number " + k);
        return n.doubleValue();
    }
}
