package com.legalpartner.service;

import com.legalpartner.model.dto.DealSpec;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * YAML-driven clause rule engine. Loads rules from {@code clause_requirements.yml}
 * and evaluates them against generated clause HTML + DealSpec.
 *
 * Supports:
 *   - Structural rules (keyword/regex/length/subclause count checks)
 *   - Deal-term enforcement (field value must appear in clause text)
 *   - Conditional rules (only apply when DealSpec has a certain value)
 *   - Cross-clause rules (one rule can apply to multiple clause types)
 *
 * All evaluation is null-safe: missing DealSpec fields skip conditional rules
 * rather than failing.
 */
@Component
@Slf4j
public class ClauseRuleEngine {

    private static final String CONFIG_PATH = "config/clause_requirements.yml";

    private List<ClauseRule> allRules = List.of();
    private List<DeterministicTemplate> deterministicTemplates = List.of();

    // ── Inner records ───────────────────────────────────────────────────

    /** A deterministic clause template rendered from structured deal values. */
    public record DeterministicTemplate(
            String id,
            /** Condition expression (same syntax as rule "when"), e.g. "support.slaResponseHours != null" */
            String appliesWhen,
            /** Target clause type to inject into, e.g. "SERVICES", "IP_RIGHTS" */
            String injectInto,
            /** PREPEND or APPEND relative to the clause body */
            String position,
            /** Template text with {{field}} placeholders */
            String template
    ) {}

    public record RuleCondition(
            /** contains_keywords | contains_any | must_include_deal_value | must_not_contain | regex_match | min_subclauses | min_length */
            String type,
            /** Keywords list (for contains_keywords, contains_any, must_not_contain) or single value */
            List<String> keywords,
            /** Single value (for regex, min_subclauses, min_length) */
            String value,
            /** DealSpec field path (for must_include_deal_value) */
            String field
    ) {}

    public record ClauseRule(
            String id,
            List<String> appliesTo,
            String severity,
            String description,
            List<RuleCondition> conditions,
            String fixStrategy,
            String fixHint,
            String injectTemplate,
            /** Conditional expression, e.g. "license.type == perpetual" */
            String when,
            /** FLAG = no fix, just warn */
            String action,
            String riskMessage
    ) {}

    public record RuleResult(
            ClauseRule rule,
            boolean passed,
            String message,
            /** What fix to apply: BLOCK, RETRY, TARGETED_RETRY, INJECT, FLAG */
            String fixAction
    ) {

        /** True if this result represents a BLOCK-level violation. */
        public boolean isBlock() {
            return !passed && "BLOCK".equals(fixAction);
        }
    }

    // ── Initialisation ──────────────────────────────────────────────────

    @PostConstruct
    void load() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) {
                log.warn("Clause requirements config not found: {} — rule engine disabled", CONFIG_PATH);
                return;
            }
            Map<String, Object> root = yaml.load(in);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawRules = (List<Map<String, Object>>) root.get("rules");
            if (rawRules == null || rawRules.isEmpty()) {
                log.warn("clause_requirements.yml has no 'rules' block");
                return;
            }

            List<ClauseRule> parsed = new ArrayList<>();
            for (Map<String, Object> raw : rawRules) {
                parsed.add(buildRule(raw));
            }
            this.allRules = Collections.unmodifiableList(parsed);

            // Parse deterministic templates
            @SuppressWarnings("unchecked")
            Map<String, Object> templateSection = (Map<String, Object>) root.get("deterministic_templates");
            if (templateSection != null && !templateSection.isEmpty()) {
                List<DeterministicTemplate> templates = new ArrayList<>();
                for (Map.Entry<String, Object> entry : templateSection.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tRaw = (Map<String, Object>) entry.getValue();
                    templates.add(new DeterministicTemplate(
                            entry.getKey(),
                            str(tRaw.get("applies_when")),
                            str(tRaw.get("inject_into")),
                            str(tRaw.get("position")),
                            str(tRaw.get("template"))
                    ));
                }
                this.deterministicTemplates = Collections.unmodifiableList(templates);
                log.info("ClauseRuleEngine loaded {} deterministic templates", deterministicTemplates.size());
            }

            long blockRules = allRules.stream().filter(r -> "BLOCK".equals(r.action())).count();
            log.info("ClauseRuleEngine loaded {} rules ({} BLOCK) from {}", allRules.size(), blockRules, CONFIG_PATH);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    @SuppressWarnings("unchecked")
    private ClauseRule buildRule(Map<String, Object> raw) {
        List<String> appliesTo = stringListOrEmpty(raw.get("applies_to"));
        List<RuleCondition> conditions = new ArrayList<>();

        Object condObj = raw.get("conditions");
        if (condObj instanceof List<?> condList) {
            for (Object c : condList) {
                if (c instanceof Map<?, ?> cm) {
                    Map<String, Object> condMap = (Map<String, Object>) cm;
                    String type = str(condMap.get("type"));
                    List<String> keywords = null;
                    String value = null;
                    String field = null;

                    Object valObj = condMap.get("value");
                    if (valObj instanceof List<?> valList) {
                        keywords = valList.stream()
                                .map(Object::toString)
                                .collect(Collectors.toList());
                    } else if (valObj != null) {
                        value = valObj.toString();
                    }
                    field = str(condMap.get("field"));
                    conditions.add(new RuleCondition(type, keywords, value, field));
                }
            }
        }

        return new ClauseRule(
                str(raw.get("id")),
                appliesTo,
                str(raw.get("severity")),
                str(raw.get("description")),
                conditions,
                str(raw.get("fix_strategy")),
                str(raw.get("fix_hint")),
                str(raw.get("inject_template")),
                str(raw.get("when")),
                str(raw.get("action")),
                str(raw.get("risk_message"))
        );
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Get applicable rules for a clause type, filtered by DealSpec conditions.
     * Rules with a "when" condition are skipped if the condition is not met.
     */
    public List<ClauseRule> getRulesForClause(String clauseType, DealSpec dealSpec) {
        return allRules.stream()
                .filter(r -> r.appliesTo().contains(clauseType) || r.appliesTo().contains("ALL"))
                .filter(r -> evaluateWhenCondition(r.when(), dealSpec))
                .collect(Collectors.toList());
    }

    /**
     * Validate a generated clause against applicable rules.
     *
     * @param clauseHtml   the generated clause HTML
     * @param clauseType   clause type key (e.g. "PAYMENT", "LIABILITY")
     * @param dealSpec     structured deal terms (nullable — degrades gracefully)
     * @return list of results, one per applicable rule, with pass/fail and fix instructions
     */
    public List<RuleResult> validate(String clauseHtml, String clauseType, DealSpec dealSpec) {
        if (clauseHtml == null || clauseHtml.isBlank()) {
            return List.of();
        }

        List<ClauseRule> applicable = getRulesForClause(clauseType, dealSpec);
        List<RuleResult> results = new ArrayList<>();

        for (ClauseRule rule : applicable) {
            boolean allConditionsMet = true;
            StringBuilder failMessages = new StringBuilder();

            for (RuleCondition cond : rule.conditions()) {
                boolean met = evaluateCondition(cond, clauseHtml, dealSpec);
                if (!met) {
                    allConditionsMet = false;
                    failMessages.append(describeFailure(cond, rule, dealSpec)).append("; ");
                }
            }

            String fixAction;
            if (allConditionsMet) {
                fixAction = null;
            } else if ("BLOCK".equals(rule.action())) {
                fixAction = "BLOCK";
            } else if ("FLAG".equals(rule.action())) {
                fixAction = "FLAG";
            } else {
                fixAction = rule.fixStrategy() != null ? rule.fixStrategy() : "FLAG";
            }

            String message = allConditionsMet
                    ? "PASS: " + rule.description()
                    : "FAIL [" + rule.id() + "]: " + failMessages.toString().trim();

            results.add(new RuleResult(rule, allConditionsMet, message, fixAction));
        }

        if (log.isDebugEnabled()) {
            long passed = results.stream().filter(RuleResult::passed).count();
            long failed = results.size() - passed;
            log.debug("ClauseRuleEngine: {} validated {} rules — {} passed, {} failed",
                    clauseType, results.size(), passed, failed);
        }

        return results;
    }

    /**
     * Build pre-generation requirements string for injection into LLM prompt.
     * Gives the model concrete instructions about what MUST appear in the clause.
     */
    public String buildRequirementsPrompt(String clauseType, DealSpec dealSpec) {
        List<ClauseRule> applicable = getRulesForClause(clauseType, dealSpec);
        if (applicable.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\nSTRICT REQUIREMENTS FOR THIS CLAUSE:\n");

        for (ClauseRule rule : applicable) {
            if ("FLAG".equals(rule.action())) continue; // don't include flag-only rules in generation prompt

            // BLOCK rules get extra emphasis in the prompt
            boolean isBlock = "BLOCK".equals(rule.action());
            String prefix = isBlock ? "- [CRITICAL — BLOCK] You MUST " : "- You MUST ";

            for (RuleCondition cond : rule.conditions()) {
                switch (cond.type()) {
                    case "contains_keywords" -> {
                        if (cond.keywords() != null) {
                            sb.append(prefix).append("include all of: ")
                              .append(String.join(", ", cond.keywords()))
                              .append("\n");
                        }
                    }
                    case "contains_any" -> {
                        if (cond.keywords() != null) {
                            sb.append(prefix).append("include at least one of: ")
                              .append(String.join(", ", cond.keywords()))
                              .append("\n");
                        }
                    }
                    case "must_include_deal_value" -> {
                        if (cond.field() != null && dealSpec != null) {
                            String formatted = dealSpec.resolveFieldFormatted(cond.field());
                            if (formatted != null) {
                                sb.append(prefix).append("include this exact value: ")
                                  .append(describeDealField(cond.field()))
                                  .append(" = ").append(formatted).append("\n");
                            }
                        }
                    }
                    case "must_not_contain" -> {
                        if (cond.keywords() != null) {
                            String notPrefix = isBlock ? "- [CRITICAL — BLOCK] You MUST NOT " : "- You MUST NOT ";
                            sb.append(notPrefix).append("include any of: ")
                              .append(String.join(", ", cond.keywords()))
                              .append("\n");
                        }
                    }
                    case "min_subclauses" -> {
                        String count = cond.value() != null ? cond.value() : "3";
                        sb.append(prefix).append("include at least ").append(count)
                          .append(" sub-clauses\n");
                    }
                    case "min_length" -> {
                        String len = cond.value() != null ? cond.value() : "300";
                        sb.append("- Clause must be substantive (at least ").append(len)
                          .append(" characters)\n");
                    }
                    case "regex_match" -> {
                        // Don't expose raw regex to LLM — translate to human-readable
                        if (cond.value() != null) {
                            sb.append("- Must reference: ").append(humanizeRegex(cond.value())).append("\n");
                        }
                    }
                }
            }

            // Append fix hint as additional guidance
            if (rule.fixHint() != null && !rule.fixHint().isBlank()) {
                sb.append("  Guidance: ").append(rule.fixHint()).append("\n");
            }
        }

        // Add concrete deal values summary if available
        String dealValues = buildDealValuesSummary(clauseType, dealSpec);
        if (!dealValues.isEmpty()) {
            sb.append("\nDEAL VALUES TO USE (use these exact numbers/terms):\n");
            sb.append(dealValues);
        }

        return sb.toString();
    }

    /**
     * Get deterministic templates applicable to a clause type, filtered by DealSpec conditions.
     * Returns templates whose {@code applies_when} condition is satisfied and whose
     * {@code inject_into} matches the given clause type.
     */
    public List<DeterministicTemplate> getDeterministicTemplates(String clauseType, DealSpec dealSpec) {
        if (dealSpec == null || deterministicTemplates.isEmpty()) return List.of();
        return deterministicTemplates.stream()
                .filter(t -> clauseType.equals(t.injectInto()))
                .filter(t -> evaluateWhenCondition(t.appliesWhen(), dealSpec))
                .collect(Collectors.toList());
    }

    /**
     * Check if any BLOCK-level violations exist in the results.
     */
    public boolean hasBlockViolations(List<RuleResult> results) {
        return results != null && results.stream().anyMatch(RuleResult::isBlock);
    }

    /**
     * Extract only the BLOCK-level violations from results.
     */
    public List<RuleResult> getBlockViolations(List<RuleResult> results) {
        if (results == null) return List.of();
        return results.stream().filter(RuleResult::isBlock).collect(Collectors.toList());
    }

    // ── Condition evaluation ────────────────────────────────────────────

    private boolean evaluateCondition(RuleCondition cond, String clauseHtml, DealSpec dealSpec) {
        String textLower = clauseHtml.toLowerCase();
        // Also strip HTML tags for text-content matching
        String plainText = clauseHtml.replaceAll("<[^>]+>", " ").toLowerCase();

        return switch (cond.type()) {
            case "contains_keywords" -> {
                if (cond.keywords() == null) yield true;
                yield cond.keywords().stream()
                        .allMatch(kw -> plainText.contains(kw.toLowerCase()));
            }

            case "contains_any" -> {
                // OR logic: at least ONE of the listed phrases must appear
                if (cond.keywords() == null) yield true;
                yield cond.keywords().stream()
                        .anyMatch(kw -> plainText.contains(kw.toLowerCase()));
            }

            case "must_include_deal_value" -> {
                if (dealSpec == null || cond.field() == null) yield true; // skip if no spec
                Object val = dealSpec.resolveField(cond.field());
                if (val == null) yield true; // field not in spec, skip check
                // Check both raw value and formatted value
                String rawStr = val.toString().toLowerCase();
                boolean found = plainText.contains(rawStr);
                if (!found) {
                    // Try formatted version for monetary amounts
                    String formatted = dealSpec.resolveFieldFormatted(cond.field());
                    if (formatted != null) {
                        found = plainText.contains(formatted.toLowerCase());
                    }
                }
                // For numbers, also check with commas
                if (!found && val instanceof Number n) {
                    String withCommas = String.format("%,d", n.longValue());
                    found = plainText.contains(withCommas);
                }
                yield found;
            }

            case "must_not_contain" -> {
                if (cond.keywords() == null) yield true;
                yield cond.keywords().stream()
                        .noneMatch(phrase -> plainText.contains(phrase.toLowerCase()));
            }

            case "regex_match" -> {
                if (cond.value() == null) yield true;
                try {
                    Pattern p = Pattern.compile(cond.value(), Pattern.CASE_INSENSITIVE);
                    yield p.matcher(plainText).find();
                } catch (Exception e) {
                    log.warn("Invalid regex in rule condition: {}", cond.value());
                    yield true; // don't fail on bad regex
                }
            }

            case "min_subclauses" -> {
                if (cond.value() == null) yield true;
                int required = Integer.parseInt(cond.value());
                // Count sub-clause markers: <p class="clause-sub"> or numbered sub-headings
                int count = countSubclauses(clauseHtml);
                yield count >= required;
            }

            case "min_length" -> {
                if (cond.value() == null) yield true;
                int required = Integer.parseInt(cond.value());
                // Count plain text length (strip HTML)
                String plain = clauseHtml.replaceAll("<[^>]+>", "").trim();
                yield plain.length() >= required;
            }

            default -> {
                log.warn("Unknown condition type: {}", cond.type());
                yield true;
            }
        };
    }

    /**
     * Evaluate a "when" condition string against the DealSpec.
     * Supports: "field == value", "field != null", "field != value"
     * Returns true if no condition (always applies) or if the condition is met.
     */
    private boolean evaluateWhenCondition(String when, DealSpec dealSpec) {
        if (when == null || when.isBlank()) return true;
        if (dealSpec == null) return false; // conditional rules require a spec

        // Parse: "field == value" or "field != null" or "field != value"
        String[] parts;
        boolean isNotEquals = false;

        if (when.contains("!=")) {
            parts = when.split("!=", 2);
            isNotEquals = true;
        } else if (when.contains("==")) {
            parts = when.split("==", 2);
        } else {
            log.warn("Unparseable 'when' condition: {}", when);
            return true;
        }

        if (parts.length != 2) return true;

        String fieldPath = parts[0].trim();
        String expected = parts[1].trim();

        Object actual = dealSpec.resolveField(fieldPath);

        if ("null".equals(expected)) {
            return isNotEquals ? (actual != null) : (actual == null);
        }

        if (actual == null) return false; // field is null but expected a value

        String actualStr = actual.toString().toLowerCase().trim();
        String expectedStr = expected.toLowerCase().trim();

        // For booleans, normalize
        if (actual instanceof Boolean b) {
            actualStr = b.toString();
            expectedStr = expected.trim();
        }

        return isNotEquals ? !actualStr.equals(expectedStr) : actualStr.equals(expectedStr);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private int countSubclauses(String html) {
        int count = 0;
        String lower = html.toLowerCase();
        // Count clause-sub paragraphs
        int idx = 0;
        while ((idx = lower.indexOf("class=\"clause-sub\"", idx)) >= 0) {
            count++;
            idx++;
        }
        // Also count numbered sub-headings like (a), (b), (i), (ii)
        if (count == 0) {
            // Fallback: count <p> or <li> tags as approximate sub-clause count
            idx = 0;
            while ((idx = lower.indexOf("<p", idx)) >= 0) {
                count++;
                idx++;
            }
            // <li> tags also count
            idx = 0;
            int liCount = 0;
            while ((idx = lower.indexOf("<li", idx)) >= 0) {
                liCount++;
                idx++;
            }
            count = Math.max(count, liCount);
        }
        return count;
    }

    private String describeFailure(RuleCondition cond, ClauseRule rule, DealSpec dealSpec) {
        return switch (cond.type()) {
            case "contains_keywords" ->
                    "Missing keywords: " + (cond.keywords() != null ? String.join(", ", cond.keywords()) : "?");
            case "contains_any" ->
                    "Missing at least one of: " + (cond.keywords() != null ? String.join(", ", cond.keywords()) : "?");
            case "must_include_deal_value" -> {
                String formatted = dealSpec != null ? dealSpec.resolveFieldFormatted(cond.field()) : null;
                yield "Missing deal value: " + describeDealField(cond.field())
                        + (formatted != null ? " (" + formatted + ")" : "");
            }
            case "must_not_contain" ->
                    "Contains prohibited phrase(s): " + (cond.keywords() != null ? String.join(", ", cond.keywords()) : "?");
            case "regex_match" ->
                    "Missing required pattern: " + humanizeRegex(cond.value());
            case "min_subclauses" ->
                    "Insufficient sub-clauses (need at least " + cond.value() + ")";
            case "min_length" ->
                    "Clause too short (need at least " + cond.value() + " characters)";
            default -> "Unknown condition failure: " + cond.type();
        };
    }

    /**
     * Human-readable description of a DealSpec field path.
     */
    private String describeDealField(String fieldPath) {
        if (fieldPath == null) return "unknown";
        return switch (fieldPath) {
            case "fees.licenseFee" -> "license fee";
            case "fees.maintenanceFee" -> "maintenance fee";
            case "fees.billingCycle" -> "billing cycle";
            case "license.users" -> "authorized users";
            case "license.locations" -> "authorized locations";
            case "license.type" -> "license type";
            case "license.deployment" -> "deployment model";
            case "legal.jurisdiction" -> "governing jurisdiction";
            case "legal.arbitration" -> "arbitration body";
            case "legal.liabilityCap" -> "liability cap";
            case "legal.noticeDays" -> "notice period (days)";
            case "support.slaResponseHours" -> "SLA response time (hours)";
            case "support.uptimeSla" -> "uptime SLA (%)";
            case "support.coverage" -> "support coverage";
            case "security.escrow" -> "source code escrow";
            case "security.soc2" -> "SOC 2 compliance";
            case "security.iso27001" -> "ISO 27001 certification";
            default -> fieldPath.replace('.', ' ');
        };
    }

    /**
     * Translate a regex pattern to a human-readable description for LLM prompts.
     */
    private String humanizeRegex(String regex) {
        if (regex == null) return "";
        // Common patterns
        if (regex.contains("USD|EUR|GBP")) return "currency denomination (USD, EUR, GBP, etc.)";
        return regex.replaceAll("[\\\\()?|+*]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Build a summary of concrete deal values relevant to a clause type,
     * for injection into the generation prompt.
     */
    private String buildDealValuesSummary(String clauseType, DealSpec dealSpec) {
        if (dealSpec == null) return "";

        StringBuilder sb = new StringBuilder();

        if ("PAYMENT".equals(clauseType)) {
            appendIfPresent(sb, "License fee", dealSpec.resolveFieldFormatted("fees.licenseFee"));
            appendIfPresent(sb, "Maintenance fee", dealSpec.resolveFieldFormatted("fees.maintenanceFee"));
            appendIfPresent(sb, "Billing cycle", dealSpec.resolveFieldFormatted("fees.billingCycle"));
            appendIfPresent(sb, "Payment terms", dealSpec.resolveFieldFormatted("fees.paymentTerms"));
        } else if ("SERVICES".equals(clauseType)) {
            appendIfPresent(sb, "Authorized users", dealSpec.resolveFieldFormatted("license.users"));
            appendIfPresent(sb, "Deployment", dealSpec.resolveFieldFormatted("license.deployment"));
            appendIfPresent(sb, "SLA response", dealSpec.resolveFieldFormatted("support.slaResponseHours"));
            appendIfPresent(sb, "Uptime SLA", dealSpec.resolveFieldFormatted("support.uptimeSla"));
        } else if ("LIABILITY".equals(clauseType)) {
            appendIfPresent(sb, "Liability cap", dealSpec.resolveFieldFormatted("legal.liabilityCap"));
        } else if ("TERMINATION".equals(clauseType)) {
            appendIfPresent(sb, "Notice period", dealSpec.resolveFieldFormatted("legal.noticeDays"));
            appendIfPresent(sb, "License type", dealSpec.resolveFieldFormatted("license.type"));
        } else if ("GOVERNING_LAW".equals(clauseType)) {
            appendIfPresent(sb, "Jurisdiction", dealSpec.resolveFieldFormatted("legal.jurisdiction"));
            appendIfPresent(sb, "Court", dealSpec.resolveFieldFormatted("legal.court"));
            appendIfPresent(sb, "Arbitration", dealSpec.resolveFieldFormatted("legal.arbitration"));
        } else if ("IP_RIGHTS".equals(clauseType)) {
            appendIfPresent(sb, "Derivative rights", dealSpec.resolveFieldFormatted("license.derivativeRights"));
            appendIfPresent(sb, "Source code escrow", dealSpec.resolveFieldFormatted("security.escrow"));
        } else if ("DATA_PROTECTION".equals(clauseType)) {
            appendIfPresent(sb, "SOC 2", dealSpec.resolveFieldFormatted("security.soc2"));
            appendIfPresent(sb, "ISO 27001", dealSpec.resolveFieldFormatted("security.iso27001"));
        }

        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    // ── YAML parsing helpers ────────────────────────────────────────────

    private static String str(Object v) {
        return (v instanceof String s && !s.isBlank()) ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListOrEmpty(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object x : list) {
                if (x != null) out.add(x.toString());
            }
            return Collections.unmodifiableList(out);
        }
        return List.of();
    }
}
