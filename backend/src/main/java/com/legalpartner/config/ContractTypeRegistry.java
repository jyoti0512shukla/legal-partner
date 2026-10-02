package com.legalpartner.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Single source of truth for per-template configuration. Replaces the
 * hardcoded switches in DraftService.defaultSections and
 * DraftContextRetriever.mapTemplateToDocumentType.
 *
 * Adding a new contract template: one block in
 * {@code resources/config/contract_types.yml}. No Java changes.
 */
@Component
@Slf4j
public class ContractTypeRegistry {

    private static final String CONFIG_PATH = "config/contract_types.yml";
    /** Template id used when the request's template isn't in the registry. */
    private static final String FALLBACK_TEMPLATE = "msa";

    public record ContractTypeConfig(
            String templateId,
            String documentType,
            String displayName,
            String description,
            String refPrefix,
            List<String> defaultSections,
            String contractMode,
            List<String> partyRoles,
            List<String> bannedTerms,
            List<String> requiredFields,
            List<String> recommendedFields,
            /** Contract-type code the reviewer uses (risk_questions.yml keys, e.g. SAAS, SOFTWARE_LICENSE). */
            String reviewType
    ) {
        /** Party A role (e.g. "Licensor", "Provider"). Falls back to "Party A". */
        public String partyARole() { return partyRoles != null && partyRoles.size() > 0 ? partyRoles.get(0) : "Party A"; }
        /** Party B role (e.g. "Licensee", "Customer"). Falls back to "Party B". */
        public String partyBRole() { return partyRoles != null && partyRoles.size() > 1 ? partyRoles.get(1) : "Party B"; }
    }

    private Map<String, ContractTypeConfig> byId = Map.of();
    private Map<String, String> fieldLabels = Map.of();

    @PostConstruct
    void load() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Missing contract type config: " + CONFIG_PATH);
            }
            Map<String, Object> root = yaml.load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> types = (Map<String, Object>) root.get("contract_types");
            if (types == null || types.isEmpty()) {
                throw new IllegalStateException("contract_types.yml has no 'contract_types' block");
            }
            Map<String, ContractTypeConfig> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : types.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) e.getValue();
                out.put(e.getKey().toLowerCase(), build(e.getKey().toLowerCase(), raw));
            }
            this.byId = Collections.unmodifiableMap(out);
            Map<String, String> labels = new LinkedHashMap<>();
            if (root.get("field_labels") instanceof Map<?, ?> fl) fl.forEach((k, v) -> labels.put(String.valueOf(k), String.valueOf(v)));
            this.fieldLabels = Collections.unmodifiableMap(labels);
            log.info("ContractTypeRegistry loaded {} templates from {}", out.size(), CONFIG_PATH);
            if (!byId.containsKey(FALLBACK_TEMPLATE)) {
                log.warn("Fallback template '{}' is not defined in contract_types.yml", FALLBACK_TEMPLATE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    private ContractTypeConfig build(String id, Map<String, Object> raw) {
        return new ContractTypeConfig(
                id,
                stringOrDefault(raw.get("document_type"), null),
                stringOrDefault(raw.get("display_name"), id),
                stringOrDefault(raw.get("description"), ""),
                stringOrDefault(raw.get("ref_prefix"), null),
                stringListOrEmpty(raw.get("default_sections")),
                stringOrDefault(raw.get("contract_mode"), null),
                stringListOrEmpty(raw.get("party_roles")),
                stringListOrEmpty(raw.get("banned_terms")),
                stringListOrEmpty(raw.get("required_fields")),
                stringListOrEmpty(raw.get("recommended_fields")),
                stringOrDefault(raw.get("review_type"), stringOrDefault(raw.get("document_type"), null))
        );
    }

    /** Get the expected contract mode for scratchpad validation. */
    public String contractMode(String templateId) {
        ContractTypeConfig c = get(templateId);
        return c != null && c.contractMode() != null ? c.contractMode() : "";
    }

    /** Get the party role names for this contract type. */
    public List<String> partyRoles(String templateId) {
        ContractTypeConfig c = get(templateId);
        return c != null && c.partyRoles() != null ? c.partyRoles() : List.of("Party A", "Party B");
    }

    /** Get banned terms for this contract type. */
    public List<String> bannedTerms(String templateId) {
        ContractTypeConfig c = get(templateId);
        return c != null && c.bannedTerms() != null ? c.bannedTerms() : List.of();
    }

    /** Lookup by template id (case-insensitive, normalizes hyphens to underscores). Falls back to msa if unknown. */
    public ContractTypeConfig get(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return byId.get(FALLBACK_TEMPLATE);
        }
        // Normalize: "software-license" → "software_license"
        String normalized = templateId.toLowerCase().replace("-", "_");
        ContractTypeConfig c = byId.get(normalized);
        if (c != null) return c;
        // Try original (in case YAML uses hyphens)
        c = byId.get(templateId.toLowerCase());
        return c != null ? c : byId.get(FALLBACK_TEMPLATE);
    }

    /** Get the default sections to use when the planner fails / undercounts. */
    public List<String> defaultSections(String templateId) {
        ContractTypeConfig c = get(templateId);
        return c != null ? c.defaultSections() : List.of();
    }

    /** Map a template id to its DocumentType tag for RAG scoping. Null if unmapped. */
    public String documentType(String templateId) {
        ContractTypeConfig c = get(templateId);
        return c != null ? c.documentType() : null;
    }

    /**
     * Contract-type code the reviewer should use for drafts of this template
     * (falls back to document_type). Drafting and review share this mapping so a
     * draft is reviewed against the questions and required clauses of its own type.
     */
    public String reviewType(String templateId) {
        ContractTypeConfig c = get(templateId);
        return c != null ? c.reviewType() : null;
    }

    /** Display label for a DealSpec field path (contract_types.yml field_labels); the path itself if unlabelled. */
    public String fieldLabel(String fieldPath) {
        return fieldLabels.getOrDefault(fieldPath, fieldPath);
    }

    public Map<String, String> fieldLabels() { return fieldLabels; }

    /** Template whose display name equals {@code name} (case-insensitive), if any. */
    public java.util.Optional<ContractTypeConfig> findByDisplayName(String name) {
        if (name == null) return java.util.Optional.empty();
        return byId.values().stream().filter(c -> name.equalsIgnoreCase(c.displayName())).findFirst();
    }

    /** True if the template id is defined (no fallback). */
    public boolean isKnown(String templateId) {
        return templateId != null && byId.containsKey(templateId.toLowerCase().replace("-", "_"));
    }

    public Set<String> allTemplateIds() { return byId.keySet(); }

    private static String stringOrDefault(Object v, String d) {
        return (v instanceof String s && !s.isBlank()) ? s : d;
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
