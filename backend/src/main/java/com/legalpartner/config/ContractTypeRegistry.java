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
            List<String> defaultSections
    ) {}

    private Map<String, ContractTypeConfig> byId = Map.of();

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
                stringListOrEmpty(raw.get("default_sections"))
        );
    }

    /** Lookup by template id (case-insensitive). Falls back to msa if unknown. */
    public ContractTypeConfig get(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return byId.get(FALLBACK_TEMPLATE);
        }
        ContractTypeConfig c = byId.get(templateId.toLowerCase());
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
