package com.legalpartner.service;

import com.legalpartner.model.dto.DealSpec;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Golden Clause Library — curated, templatized legal clauses for deterministic fallback.
 *
 * At draft time, retrieves the best matching golden clause based on hierarchical fallback:
 *   contract_type + jurisdiction + industry → contract_type + jurisdiction →
 *   contract_type + "default" → "default" + "default"
 *
 * Resolves {{placeholder}} template variables from DealSpec values.
 * Handles {{#if field}}...{{/if}} conditional blocks.
 * CRITICAL: Never outputs unresolved placeholders — strips them if value is null.
 *
 * Usage:
 *   - Primary: deterministic fallback when LLM-generated clauses fail QA
 *   - Secondary: base template for clause generation (pre-populated with deal terms)
 *   - Future: firm-specific clause additions via UI
 */
@Component
@Slf4j
public class GoldenClauseLibrary {

    private static final String CONFIG_PATH = "config/golden_clauses.yml";

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^#/}][^}]*)}}");
    private static final Pattern CONDITIONAL_PATTERN = Pattern.compile(
            "\\{\\{#if\\s+([^}]+)}}(.*?)\\{\\{/if}}", Pattern.DOTALL);

    /**
     * Immutable golden clause record.
     */
    public record GoldenClause(
            String id,
            String clauseType,
            String contractType,
            String jurisdiction,
            String template,
            Map<String, String> industryAdditions,
            String source,
            int qualityScore
    ) {}

    /** All clauses indexed by clauseType → list of entries (sorted by quality descending). */
    private Map<String, List<GoldenClause>> byClauseType = Map.of();

    /**
     * Convenience alias map: short placeholder names → DealSpec field paths.
     * Allows templates to use {{licensor}} instead of {{partyA.name}}.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("license_fee", "fees.licenseFee"),
            Map.entry("maintenance_fee", "fees.maintenanceFee"),
            Map.entry("subscription_fee", "fees.subscriptionFee"),
            Map.entry("users", "license.users"),
            Map.entry("locations", "license.locations"),
            Map.entry("sla_hours", "support.slaResponseHours"),
            Map.entry("support_coverage", "support.coverage"),
            Map.entry("patch_frequency", "support.patchFrequency"),
            Map.entry("uptime_sla", "support.uptimeSla"),
            Map.entry("jurisdiction", "legal.jurisdiction"),
            Map.entry("court", "legal.court"),
            Map.entry("notice_days", "legal.noticeDays"),
            Map.entry("cure_days", "legal.cureDays"),
            Map.entry("survival_years", "legal.survivalYears"),
            Map.entry("notice_period", "legal.noticePeriod"),
            Map.entry("salary", "compensation.salary"),
            Map.entry("license_type", "license.type"),
            Map.entry("software_name", "_software_name")
    );

    /**
     * Role-based party placeholder names.
     * Maps placeholder → expected role name (case-insensitive match against partyA/partyB roles).
     * Falls back to partyA for first-listed roles and partyB for second-listed.
     */
    private static final Map<String, String> PARTY_A_ROLES = Map.ofEntries(
            Map.entry("licensor", "Licensor"),
            Map.entry("service_provider", "Service Provider"),
            Map.entry("provider", "Provider"),
            Map.entry("employer", "Employer"),
            Map.entry("supplier", "Supplier"),
            Map.entry("disclosing_party", "Disclosing Party")
    );
    private static final Map<String, String> PARTY_B_ROLES = Map.ofEntries(
            Map.entry("licensee", "Licensee"),
            Map.entry("client", "Client"),
            Map.entry("customer", "Customer"),
            Map.entry("employee", "Employee"),
            Map.entry("buyer", "Buyer"),
            Map.entry("receiving_party", "Receiving Party")
    );

    @PostConstruct
    void load() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) {
                log.warn("Golden clause config not found at {}; library will be empty", CONFIG_PATH);
                return;
            }
            Map<String, Object> root = yaml.load(in);

            // Load placeholder defaults
            @SuppressWarnings("unchecked")
            Map<String, Object> defaults = (Map<String, Object>) root.get("placeholder_defaults");
            if (defaults != null) {
                Map<String, String> pd = new LinkedHashMap<>();
                defaults.forEach((k, v) -> pd.put(k.toString(), v != null ? v.toString() : ""));
                this.placeholderDefaults = Collections.unmodifiableMap(pd);
                log.info("GoldenClauseLibrary: loaded {} placeholder defaults", pd.size());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> clauses = (Map<String, Object>) root.get("clauses");
            if (clauses == null || clauses.isEmpty()) {
                log.warn("golden_clauses.yml has no 'clauses' block");
                return;
            }

            Map<String, List<GoldenClause>> index = new LinkedHashMap<>();

            for (Map.Entry<String, Object> entry : clauses.entrySet()) {
                String clauseType = entry.getKey().toUpperCase();
                Object value = entry.getValue();

                List<GoldenClause> clauseList = parseClauseEntries(clauseType, value);
                // Merge with existing if same clause type appears multiple times in YAML
                index.computeIfAbsent(clauseType, k -> new ArrayList<>()).addAll(clauseList);
            }

            // Sort each list by quality descending
            for (List<GoldenClause> list : index.values()) {
                list.sort(Comparator.comparingInt(GoldenClause::qualityScore).reversed());
            }

            this.byClauseType = Collections.unmodifiableMap(index);
            int total = index.values().stream().mapToInt(List::size).sum();
            log.info("GoldenClauseLibrary loaded {} clauses across {} types from {}",
                    total, index.size(), CONFIG_PATH);

        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<GoldenClause> parseClauseEntries(String clauseType, Object value) {
        List<GoldenClause> results = new ArrayList<>();

        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    results.add(buildClause(clauseType, (Map<String, Object>) raw));
                }
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private GoldenClause buildClause(String clauseType, Map<String, Object> raw) {
        Map<String, String> industryAdditions = new LinkedHashMap<>();
        Object additions = raw.get("industry_additions");
        if (additions instanceof Map<?, ?> addMap) {
            for (Map.Entry<?, ?> e : addMap.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    industryAdditions.put(e.getKey().toString().toLowerCase(), e.getValue().toString());
                }
            }
        }

        return new GoldenClause(
                stringOrDefault(raw.get("id"), "UNKNOWN"),
                clauseType,
                stringOrDefault(raw.get("contract_type"), "default").toLowerCase(),
                stringOrDefault(raw.get("jurisdiction"), "default").toLowerCase(),
                stringOrDefault(raw.get("template"), ""),
                Collections.unmodifiableMap(industryAdditions),
                stringOrDefault(raw.get("source"), ""),
                intOrDefault(raw.get("quality_score"), 3)
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Retrieve the best matching golden clause for a given context.
     * Hierarchical fallback:
     *   1. clauseType + contractType + jurisdiction + industry (quality_score tiebreaker)
     *   2. clauseType + contractType + jurisdiction
     *   3. clauseType + contractType + "default" jurisdiction
     *   4. clauseType + any contractType + "default" jurisdiction (generic)
     *   5. Empty if nothing found
     *
     * @param clauseType   e.g. "IP_RIGHTS", "PAYMENT", "LIABILITY"
     * @param contractType e.g. "software_license", "saas", "nda"
     * @param jurisdiction e.g. "us", "india", "uk", "default"
     * @param industry     e.g. "financial_services", "healthcare" (nullable)
     * @return best matching golden clause, or empty
     */
    public Optional<GoldenClause> retrieve(String clauseType, String contractType,
                                            String jurisdiction, String industry) {
        if (clauseType == null) return Optional.empty();

        List<GoldenClause> candidates = byClauseType.get(clauseType.toUpperCase());
        if (candidates == null || candidates.isEmpty()) return Optional.empty();

        String ct = contractType != null ? contractType.toLowerCase().replace("-", "_") : "default";
        String jur = jurisdiction != null ? jurisdiction.toLowerCase().replace("-", "_") : "default";
        String ind = industry != null ? industry.toLowerCase() : null;

        // Level 1: exact contract_type + jurisdiction, prefer those with industry_additions matching
        Optional<GoldenClause> match = candidates.stream()
                .filter(c -> c.contractType().equals(ct) && c.jurisdiction().equals(jur))
                .max(Comparator.comparingInt(c -> scoreForIndustry(c, ind)));
        if (match.isPresent()) return match;

        // Level 2: contract_type + "default" jurisdiction
        if (!jur.equals("default")) {
            match = candidates.stream()
                    .filter(c -> c.contractType().equals(ct) && c.jurisdiction().equals("default"))
                    .max(Comparator.comparingInt(c -> scoreForIndustry(c, ind)));
            if (match.isPresent()) return match;
        }

        // Level 3: any clause of this type with "default" contract_type
        match = candidates.stream()
                .filter(c -> c.contractType().equals("default"))
                .max(Comparator.comparingInt(GoldenClause::qualityScore));
        if (match.isPresent()) return match;

        // Level 4: just take the highest quality clause of this type
        return candidates.stream()
                .max(Comparator.comparingInt(GoldenClause::qualityScore));
    }

    /**
     * Retrieve all golden clauses for a given clause type and contract type.
     * Useful when multiple clauses should be composed together (e.g., all LIABILITY sub-clauses).
     */
    public List<GoldenClause> retrieveAll(String clauseType, String contractType,
                                           String jurisdiction, String industry) {
        if (clauseType == null) return List.of();

        List<GoldenClause> candidates = byClauseType.get(clauseType.toUpperCase());
        if (candidates == null || candidates.isEmpty()) return List.of();

        String ct = contractType != null ? contractType.toLowerCase().replace("-", "_") : "default";
        String jur = jurisdiction != null ? jurisdiction.toLowerCase().replace("-", "_") : "default";

        // Return all clauses matching contract_type and (jurisdiction or default)
        return candidates.stream()
                .filter(c -> c.contractType().equals(ct)
                        && (c.jurisdiction().equals(jur) || c.jurisdiction().equals("default")))
                .toList();
    }

    /**
     * Resolve a golden clause template with DealSpec values.
     * Replaces {{placeholder}} with actual values. Handles {{#if}} conditionals.
     * CRITICAL: Never outputs unresolved placeholders — strips them if value is null.
     *
     * @param clause   the golden clause to resolve
     * @param dealSpec deal terms (nullable — all placeholders will be stripped)
     * @param industry industry for appending industry_additions (nullable)
     * @return fully resolved clause text with no remaining {{...}} markers
     */
    public String resolve(GoldenClause clause, DealSpec dealSpec, String industry) {
        if (clause == null) return "";

        String text = clause.template();

        // Step 1: Process conditional blocks FIRST
        text = processConditionals(text, dealSpec);

        // Step 2: Resolve placeholders
        text = resolvePlaceholders(text, dealSpec);

        // Step 3: Strip any remaining unresolved placeholders (safety net — no data pollution)
        text = stripUnresolved(text);

        // Step 4: Append industry-specific additions if applicable
        if (industry != null && !industry.isBlank()) {
            String addition = clause.industryAdditions().get(industry.toLowerCase());
            if (addition != null && !addition.isBlank()) {
                text = text.stripTrailing() + "\n\n" + addition.strip();
            }
        }

        return text.strip();
    }

    /**
     * Convenience overload — resolves without industry additions.
     */
    public String resolve(GoldenClause clause, DealSpec dealSpec) {
        return resolve(clause, dealSpec, null);
    }

    /**
     * Check if the library has any clauses for a given clause type.
     */
    public boolean hasClausesFor(String clauseType) {
        if (clauseType == null) return false;
        List<GoldenClause> list = byClauseType.get(clauseType.toUpperCase());
        return list != null && !list.isEmpty();
    }

    /**
     * Get all available clause types in the library.
     */
    public Set<String> availableClauseTypes() {
        return Collections.unmodifiableSet(byClauseType.keySet());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INTERNAL — Template Resolution
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Process {{#if field}}...{{/if}} conditional blocks.
     * Includes the block content only if the field resolves to a truthy value.
     */
    private String processConditionals(String text, DealSpec dealSpec) {
        if (text == null) return "";

        Matcher m = CONDITIONAL_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String fieldPath = m.group(1).trim();
            String blockContent = m.group(2);

            boolean include = isTruthy(resolveFieldValue(fieldPath, dealSpec));
            m.appendReplacement(sb, Matcher.quoteReplacement(include ? blockContent : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Replace all {{placeholder}} occurrences with resolved values.
     */
    private String resolvePlaceholders(String text, DealSpec dealSpec) {
        if (text == null || dealSpec == null) return text != null ? text : "";

        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String placeholder = m.group(1).trim();
            String resolved = resolveValue(placeholder, dealSpec);
            if (resolved != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
            }
            // If null, leave in place — will be stripped in next phase
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolve a placeholder to its string value.
     * Checks aliases first, then tries direct DealSpec field path resolution.
     */
    private String resolveValue(String placeholder, DealSpec dealSpec) {
        if (dealSpec == null) return null;

        // Handle role-based party placeholders (partyA-oriented roles)
        if (PARTY_A_ROLES.containsKey(placeholder)) {
            return resolvePartyByRole(dealSpec, PARTY_A_ROLES.get(placeholder), true);
        }
        // Handle role-based party placeholders (partyB-oriented roles)
        if (PARTY_B_ROLES.containsKey(placeholder)) {
            return resolvePartyByRole(dealSpec, PARTY_B_ROLES.get(placeholder), false);
        }

        // Check alias map
        String fieldPath = ALIASES.get(placeholder);
        if (fieldPath != null) {
            if ("_software_name".equals(fieldPath)) {
                return "the Software";
            }
            String fieldValue = resolveFormattedField(fieldPath, dealSpec);
            return fieldValue; // null values handled by stripUnresolved
        }

        // Try direct DealSpec field path (e.g. "fees.billingCycle", "support.uptimeSla")
        if (placeholder.contains(".")) {
            return resolveFormattedField(placeholder, dealSpec);
        }

        // Try as simple field name with common parent objects
        return resolveFormattedField(placeholder, dealSpec);
    }

    /**
     * Resolve a dotted field path and return formatted string.
     */
    private String resolveFormattedField(String fieldPath, DealSpec dealSpec) {
        if (dealSpec == null || fieldPath == null) return null;
        String formatted = dealSpec.resolveFieldFormatted(fieldPath);
        return formatted;
    }

    /**
     * Resolve raw field value (for truthiness checks in conditionals).
     */
    private Object resolveFieldValue(String fieldPath, DealSpec dealSpec) {
        if (dealSpec == null || fieldPath == null) return null;

        // Check aliases
        String aliasPath = ALIASES.get(fieldPath);
        if (aliasPath != null && !"_software_name".equals(aliasPath)) {
            fieldPath = aliasPath;
        }

        // Direct field resolution
        if (fieldPath.contains(".")) {
            return dealSpec.resolveField(fieldPath);
        }

        // Try common parent objects for unqualified field names
        for (String prefix : List.of("license.", "fees.", "support.", "security.", "legal.", "partyA.", "partyB.")) {
            Object val = dealSpec.resolveField(prefix + fieldPath);
            if (val != null) return val;
        }
        return null;
    }

    /**
     * Resolve a party name from DealSpec by matching role.
     * Searches both partyA and partyB for the given role (case-insensitive).
     * If no role match is found, falls back to partyA (if defaultToA=true) or partyB.
     *
     * @param dealSpec    the deal terms
     * @param targetRole  the role to match (e.g. "Licensor", "Service Provider", "Employer")
     * @param defaultToA  if no role match, default to partyA (true) or partyB (false)
     * @return the party name, or null
     */
    private String resolvePartyByRole(DealSpec dealSpec, String targetRole, boolean defaultToA) {
        // Check partyA role
        if (dealSpec.getPartyA() != null) {
            String role = dealSpec.getPartyA().getRole();
            if (role != null && role.equalsIgnoreCase(targetRole)) {
                return dealSpec.getPartyA().getName();
            }
        }
        // Check partyB role
        if (dealSpec.getPartyB() != null) {
            String role = dealSpec.getPartyB().getRole();
            if (role != null && role.equalsIgnoreCase(targetRole)) {
                return dealSpec.getPartyB().getName();
            }
        }
        // Fallback: partyA for "A-oriented" roles (licensor, provider, employer, supplier),
        // partyB for "B-oriented" roles (licensee, customer, employee, buyer)
        if (defaultToA) {
            return dealSpec.getPartyA() != null ? dealSpec.getPartyA().getName() : null;
        } else {
            return dealSpec.getPartyB() != null ? dealSpec.getPartyB().getName() : null;
        }
    }

    /**
     * Strip any remaining unresolved {{...}} placeholders from the text.
     * This is the safety net — ensures no template syntax leaks into output.
     */
    private String stripUnresolved(String text) {
        if (text == null) return "";
        // Render unresolved placeholders as visible fill-in fields in the draft.
        // The user sees highlighted blanks — like a real contract template.
        // No guessing, no hardcoding defaults, no fallback map needed.
        if (text.contains("{{")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{([^}]+)}}").matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String placeholder = m.group(1).trim();
                // Convert placeholder_name to "Placeholder Name" for display
                String label = placeholder.replace("_", " ");
                label = label.substring(0, 1).toUpperCase() + label.substring(1);
                String fillIn = "<span class=\"placeholder\" style=\"background:#FEF3C7;border-bottom:2px dashed #D97706;padding:0 4px;\" "
                        + "data-field=\"" + placeholder + "\" title=\"Fill in: " + label + "\">"
                        + "[" + label + "]</span>";
                log.debug("Golden clause: unresolved {{{}}} → rendered as fill-in field", placeholder);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(fillIn));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        return text;
    }

    /**
     * Check if a value is "truthy" for conditional evaluation.
     */
    private boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String s) return !s.isBlank();
        return true;
    }

    /**
     * Score a clause for industry relevance (used for tiebreaking).
     * +10 for quality, +5 if it has matching industry addition.
     */
    private int scoreForIndustry(GoldenClause clause, String industry) {
        int score = clause.qualityScore() * 10;
        if (industry != null && clause.industryAdditions().containsKey(industry.toLowerCase())) {
            score += 50;
        }
        return score;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
}
