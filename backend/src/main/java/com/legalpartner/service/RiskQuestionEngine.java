package com.legalpartner.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Structured risk question engine. Loads questions from risk_questions.yml,
 * filters them per clause type / contract type, and computes deterministic risk
 * scores from YES/NO answers.
 */
@Component
@Slf4j
public class RiskQuestionEngine {

    // ── Records ──────────────────────────────────────────────────────────────

    /**
     * @param positionSensitive true when the "right" answer depends on which party we
     *        represent (e.g. mutual caps). Such questions are waived for drafts written
     *        in favour of one party — see {@link #isWaivedFor(RiskQuestion, String)}.
     */
    public record RiskQuestion(
            String id, String question, String riskIfNo, String riskIfYes,
            String category, List<String> appliesTo, int weight, boolean positionSensitive
    ) {
        public RiskQuestion(String id, String question, String riskIfNo, String riskIfYes,
                            String category, List<String> appliesTo, int weight) {
            this(id, question, riskIfNo, riskIfYes, category, appliesTo, weight, false);
        }
    }

    /** Answer recorded for a position-sensitive question that does not apply to a one-sided draft. */
    public static final String WAIVED = "WAIVED";

    public record QuestionResult(
            RiskQuestion question, boolean answered, String answer,
            String quotedEvidence
    ) {}

    public record ClauseRiskResult(
            String clauseType, String overallRisk,
            List<QuestionResult> results, boolean clausePresent
    ) {}

    public record FullRiskReport(
            String overallRisk, double riskScore,
            List<ClauseRiskResult> clauseResults,
            List<String> missingClauses, List<String> keyFindings
    ) {}

    // ── State ────────────────────────────────────────────────────────────────

    /** clauseType → list of questions */
    private final Map<String, List<RiskQuestion>> questionsByClause = new LinkedHashMap<>();

    /** contractType → list of required clause types */
    private final Map<String, List<String>> requiredClauses = new LinkedHashMap<>();

    /** Checklist clause id → display name (YAML order preserved). */
    private final Map<String, String> checklistClauses = new LinkedHashMap<>();

    /** clauseType → keywords used by the text-based clause inventory (YAML order preserved). */
    private final Map<String, List<String>> clauseKeywords = new LinkedHashMap<>();

    /** Configurable prompts loaded from YAML */
    private String systemPrompt = "";
    private String userPromptTemplate = "";
    private String extractionPrompt = "";

    /** Extraction field definitions loaded from YAML */
    private final List<ExtractionField> extractionFields = new ArrayList<>();

    public record ExtractionField(String id, String description, List<String> sectionKeywords, List<String> appliesTo) {
        public boolean appliesTo(String contractType) {
            return appliesTo == null || appliesTo.isEmpty() || appliesTo.contains(contractType);
        }
        public boolean usesPreamble() { return sectionKeywords == null || sectionKeywords.isEmpty(); }
    }

    // ── Initialization ───────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        loadQuestions();
        if (!requiredClauses.containsKey("_default")) {
            throw new IllegalStateException(
                    "risk_questions.yml must define required_clauses._default (no code fallback)");
        }
        if (checklistClauses.isEmpty()) {
            throw new IllegalStateException("risk_questions.yml must define checklist_clauses");
        }
        if (clauseKeywords.isEmpty()) {
            throw new IllegalStateException("risk_questions.yml must define clause_keywords");
        }
        log.info("RiskQuestionEngine loaded: {} clause types, {} total questions",
                questionsByClause.size(),
                questionsByClause.values().stream().mapToInt(List::size).sum());
    }

    @SuppressWarnings("unchecked")
    private void loadQuestions() {
        Yaml yaml = new Yaml();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("config/risk_questions.yml")) {
            if (is == null) {
                log.error("risk_questions.yml not found on classpath");
                return;
            }
            Map<String, Object> root = yaml.load(is);

            // Load configurable prompts
            Map<String, Object> prompts = (Map<String, Object>) root.get("prompts");
            if (prompts != null) {
                systemPrompt = prompts.getOrDefault("system", "").toString().trim();
                userPromptTemplate = prompts.getOrDefault("user_template", "").toString().trim();
                log.info("RiskQuestionEngine: loaded configurable prompts from YAML");
            }

            // Load extraction fields
            List<Map<String, Object>> extFields = (List<Map<String, Object>>) root.get("extraction_fields");
            if (extFields != null) {
                for (Map<String, Object> ef : extFields) {
                    extractionFields.add(new ExtractionField(
                            ef.getOrDefault("id", "").toString(),
                            ef.getOrDefault("description", "").toString(),
                            ef.get("section_keywords") instanceof List<?> kws
                                    ? kws.stream().map(Object::toString).collect(java.util.stream.Collectors.toList())
                                    : List.of(),
                            ef.get("applies_to") instanceof List<?> at
                                    ? at.stream().map(Object::toString).collect(java.util.stream.Collectors.toList())
                                    : List.of()
                    ));
                }
                log.info("RiskQuestionEngine: loaded {} extraction fields", extractionFields.size());
            }

            // Load extraction prompt
            extractionPrompt = root.getOrDefault("extraction_prompt", "").toString().trim();

            // Load clause questions
            Map<String, Object> clauseQs = (Map<String, Object>) root.get("clause_questions");
            if (clauseQs != null) {
                for (Map.Entry<String, Object> entry : clauseQs.entrySet()) {
                    String clauseType = entry.getKey();
                    List<Map<String, Object>> qList = (List<Map<String, Object>>) entry.getValue();
                    List<RiskQuestion> questions = new ArrayList<>();
                    for (Map<String, Object> q : qList) {
                        List<String> appliesTo = q.get("applies_to") != null
                                ? ((List<?>) q.get("applies_to")).stream()
                                        .map(Object::toString).collect(Collectors.toList())
                                : List.of();
                        int weight = q.get("weight") != null
                                ? ((Number) q.get("weight")).intValue() : 5;
                        questions.add(new RiskQuestion(
                                (String) q.get("id"),
                                (String) q.get("question"),
                                (String) q.get("risk_if_no"),
                                (String) q.get("risk_if_yes"),
                                (String) q.get("category"),
                                appliesTo,
                                weight,
                                Boolean.TRUE.equals(q.get("position_sensitive"))
                        ));
                    }
                    questionsByClause.put(clauseType, questions);
                }
            }

            // Load checklist clauses
            Map<String, Object> cl = (Map<String, Object>) root.get("checklist_clauses");
            if (cl != null) cl.forEach((k, v) -> checklistClauses.put(k, String.valueOf(v)));

            // Load clause inventory keywords
            Map<String, Object> kw = (Map<String, Object>) root.get("clause_keywords");
            if (kw != null) {
                for (Map.Entry<String, Object> entry : kw.entrySet()) {
                    clauseKeywords.put(entry.getKey(), ((List<?>) entry.getValue()).stream()
                            .map(o -> o.toString().toLowerCase()).collect(Collectors.toList()));
                }
            }

            // Load required clauses
            Map<String, Object> reqClauses = (Map<String, Object>) root.get("required_clauses");
            if (reqClauses != null) {
                for (Map.Entry<String, Object> entry : reqClauses.entrySet()) {
                    List<String> clauses = ((List<?>) entry.getValue()).stream()
                            .map(Object::toString).collect(Collectors.toList());
                    requiredClauses.put(entry.getKey(), clauses);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load risk_questions.yml: {}", e.getMessage(), e);
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Get all known clause types that have questions. */
    public Set<String> getAllClauseTypes() {
        return Collections.unmodifiableSet(questionsByClause.keySet());
    }

    /**
     * Get questions for a specific clause type, filtered by contract type.
     * If contractType is null or empty, all questions are returned.
     */
    public List<RiskQuestion> getQuestionsForClause(String clauseType, String contractType) {
        List<RiskQuestion> all = questionsByClause.getOrDefault(clauseType, List.of());
        if (contractType == null || contractType.isBlank()) {
            return all;
        }
        return all.stream()
                .filter(q -> q.appliesTo().isEmpty() || q.appliesTo().contains(contractType))
                .collect(Collectors.toList());
    }

    /**
     * A position-sensitive question (e.g. "is the cap mutual?") is waived when the document
     * was drafted in favour of one party: one-sidedness is then intentional, not a risk.
     * Limitation: this does not detect a one-sided term that favours the *other* party.
     */
    public static boolean isWaivedFor(RiskQuestion q, String clientPosition) {
        if (q == null || !q.positionSensitive() || clientPosition == null) return false;
        String p = clientPosition.trim().toUpperCase();
        return p.equals("PARTY_A") || p.equals("PARTY_B");
    }

    public static boolean isWaived(QuestionResult qr) {
        return qr != null && qr.answer() != null && WAIVED.equalsIgnoreCase(qr.answer().trim());
    }

    /** Get required clauses for a contract type. Falls back to _default. */
    public List<String> getRequiredClauses(String contractType) {
        if (contractType != null && requiredClauses.containsKey(contractType)) {
            return requiredClauses.get(contractType);
        }
        return requiredClauses.get("_default");
    }

    /** Checklist clause id → display name, from risk_questions.yml {@code checklist_clauses}. */
    public Map<String, String> getChecklistClauses() {
        return Collections.unmodifiableMap(checklistClauses);
    }

    /** Clause type → inventory keywords, from risk_questions.yml {@code clause_keywords}. */
    public Map<String, List<String>> getClauseKeywords() {
        return Collections.unmodifiableMap(clauseKeywords);
    }

    /** Contract-type codes that have a required-clause list (excluding _default). */
    public Set<String> getKnownContractTypes() {
        Set<String> out = new java.util.LinkedHashSet<>(requiredClauses.keySet());
        out.remove("_default");
        return Collections.unmodifiableSet(out);
    }

    /** Get the configurable system prompt for risk assessment. */
    public String getSystemPrompt() { return systemPrompt; }

    /** Get the configurable user prompt template. Placeholders: {{clauseType}}, {{clauseText}}, {{questions}} */
    public String getUserPromptTemplate() { return userPromptTemplate; }

    /** Get extraction fields applicable to a contract type. */
    public List<ExtractionField> getExtractionFields(String contractType) {
        return extractionFields.stream()
                .filter(f -> f.appliesTo(contractType))
                .collect(java.util.stream.Collectors.toList());
    }

    /** Get the configurable extraction prompt. */
    public String getExtractionPrompt() { return extractionPrompt; }

    // ── Risk computation ─────────────────────────────────────────────────────

    /** Compute per-clause risk from a list of QuestionResults. */
    public ClauseRiskResult computeClauseRisk(String clauseType, List<QuestionResult> results, boolean clausePresent) {
        if (!clausePresent) {
            return new ClauseRiskResult(clauseType, "HIGH", results, false);
        }
        String risk = computeClauseRiskLevel(results);
        return new ClauseRiskResult(clauseType, risk, results, true);
    }

    /**
     * Compute full risk report from per-clause results.
     * @param clauseResults  per-clause risk results (both present and missing clauses)
     * @param missingClauses clause types that are required but not found in the contract
     */
    public FullRiskReport computeFullReport(List<ClauseRiskResult> clauseResults, List<String> missingClauses) {
        return computeFullReport(clauseResults, missingClauses, Map.of());
    }

    /**
     * @param weightMultipliers question id → multiplier in (0, 1] from review calibration: questions
     *                          this firm's lawyers often correct count for less in the score.
     */
    public FullRiskReport computeFullReport(List<ClauseRiskResult> clauseResults, List<String> missingClauses,
                                            Map<String, Double> weightMultipliers) {
        double totalWeightedScore = 0;
        double totalWeight = 0;

        for (ClauseRiskResult cr : clauseResults) {
            if (!cr.clausePresent()) {
                // Missing required clause = fixed penalty. Kept below the
                // theoretical max so a single gap flags clearly without
                // swamping an otherwise well-scored contract.
                totalWeightedScore += 80.0 * 8;
                totalWeight += 8;
                continue;
            }
            for (QuestionResult qr : cr.results()) {
                if (!qr.answered() || isWaived(qr)) continue;
                double riskPoints = computeRiskPoints(qr);
                double weight = qr.question().weight() * weightMultipliers.getOrDefault(qr.question().id(), 1.0);
                totalWeightedScore += riskPoints * weight;
                totalWeight += weight;
            }
        }

        // riskScore: 0 = perfect (no risk), 100 = maximum risk
        double riskScore = totalWeight > 0 ? totalWeightedScore / totalWeight : 50.0;
        // Clamp
        riskScore = Math.max(0, Math.min(100, riskScore));

        String overallRisk = riskScore >= 60 ? "HIGH" : (riskScore >= 30 ? "MEDIUM" : "LOW");

        // Also check: if any clause is HIGH, overall is at least MEDIUM
        boolean anyClauseHigh = clauseResults.stream()
                .anyMatch(cr -> "HIGH".equals(cr.overallRisk()));
        if (anyClauseHigh && "LOW".equals(overallRisk)) {
            overallRisk = "MEDIUM";
        }

        // If 3+ clauses are HIGH, overall is HIGH
        long highCount = clauseResults.stream()
                .filter(cr -> "HIGH".equals(cr.overallRisk())).count();
        if (highCount >= 3) {
            overallRisk = "HIGH";
        }

        // Key findings: top 5 most important issues
        List<String> keyFindings = buildKeyFindings(clauseResults, missingClauses);

        return new FullRiskReport(
                overallRisk,
                Math.round(riskScore * 10.0) / 10.0,
                clauseResults,
                missingClauses,
                keyFindings
        );
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Weight-aware clause risk level. A clause is HIGH only when a heavyweight
     * (weight >= 9) HIGH-risk answer fires, or when 2+ HIGH-risk answers fire.
     * A single lower-weight HIGH-risk answer yields MEDIUM — previously any
     * single HIGH-risk answer made the whole clause HIGH, which cascaded into
     * HIGH overall ratings for well-drafted contracts.
     */
    private String computeClauseRiskLevel(List<QuestionResult> results) {
        int highHits = 0;
        boolean heavyweightHigh = false;
        boolean anyMedium = false;

        for (QuestionResult qr : results) {
            if (!qr.answered() || isWaived(qr)) continue;
            String answer = qr.answer() != null ? qr.answer().toUpperCase().trim() : "";
            boolean isYes = answer.startsWith("YES");
            boolean isNo = answer.startsWith("NO");

            String firedRisk = null;
            if (isNo) firedRisk = qr.question().riskIfNo();
            else if (isYes) firedRisk = qr.question().riskIfYes();

            if ("HIGH".equals(firedRisk)) {
                highHits++;
                if (qr.question().weight() >= 9) heavyweightHigh = true;
            } else if ("MEDIUM".equals(firedRisk)) {
                anyMedium = true;
            }
        }

        if (heavyweightHigh || highHits >= 2) return "HIGH";
        if (highHits == 1 || anyMedium) return "MEDIUM";
        return "LOW";
    }

    /** riskPoints: 0 = no risk, 100 = maximum risk */
    private double computeRiskPoints(QuestionResult qr) {
        String answer = qr.answer() != null ? qr.answer().toUpperCase().trim() : "";
        boolean isYes = answer.startsWith("YES");
        boolean isNo = answer.startsWith("NO");

        if (isNo) {
            return switch (qr.question().riskIfNo()) {
                case "HIGH" -> 100;
                case "MEDIUM" -> 60;
                case "LOW" -> 25;
                default -> 0; // INFO
            };
        }
        if (isYes) {
            return switch (qr.question().riskIfYes()) {
                case "HIGH" -> 100;
                case "MEDIUM" -> 60;
                case "LOW" -> 0;
                default -> 0;
            };
        }
        // Unanswered or unclear — assume moderate risk
        return 40;
    }

    private List<String> buildKeyFindings(List<ClauseRiskResult> clauseResults, List<String> missingClauses) {
        List<String> findings = new ArrayList<>();

        // Missing clauses first
        for (String missing : missingClauses) {
            findings.add("MISSING: " + formatClauseName(missing)
                    + " clause is required but not found in the contract.");
        }

        // Collect all HIGH-risk answered NO questions, sorted by weight descending
        record Finding(String clauseType, QuestionResult qr) {}
        List<Finding> highFindings = new ArrayList<>();

        for (ClauseRiskResult cr : clauseResults) {
            if (!cr.clausePresent()) continue;
            for (QuestionResult qr : cr.results()) {
                if (!qr.answered()) continue;
                String answer = qr.answer() != null ? qr.answer().toUpperCase().trim() : "";
                boolean isNo = answer.startsWith("NO");
                if (isNo && "HIGH".equals(qr.question().riskIfNo())) {
                    highFindings.add(new Finding(cr.clauseType(), qr));
                }
            }
        }

        highFindings.sort(Comparator.comparingInt((Finding f) -> f.qr().question().weight()).reversed());

        for (Finding f : highFindings) {
            if (findings.size() >= 5) break;
            String evidence = (f.qr().quotedEvidence() != null && !f.qr().quotedEvidence().isBlank())
                    ? " Evidence: \"" + truncate(f.qr().quotedEvidence(), 100) + "\""
                    : "";
            findings.add("HIGH RISK [" + formatClauseName(f.clauseType()) + "]: "
                    + f.qr().question().question().replace("Is there", "No").replace("Does the", "The")
                    + evidence);
        }

        return findings.subList(0, Math.min(5, findings.size()));
    }

    /** Convert CLAUSE_TYPE to "Clause Type" display format. */
    static String formatClauseName(String clauseType) {
        if (clauseType == null) return "Unknown";
        String[] parts = clauseType.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(part.substring(0, 1).toUpperCase());
            sb.append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
