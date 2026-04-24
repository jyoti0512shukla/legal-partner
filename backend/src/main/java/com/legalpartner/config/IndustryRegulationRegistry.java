package com.legalpartner.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Loads industry → jurisdiction → regulatory reference mappings from
 * {@code resources/config/industry_regulations.yml}.
 *
 * Used by DraftService to inject jurisdiction-aware regulatory context
 * into clause prompts. Adding a new industry or jurisdiction requires
 * only a YAML change — no Java modifications.
 */
@Component
@Slf4j
public class IndustryRegulationRegistry {

    private static final String CONFIG_PATH = "config/industry_regulations.yml";

    /** Each jurisdiction block: keywords to match + industry→reference map. */
    public record JurisdictionBlock(List<String> keywords, Map<String, String> industries) {}

    private List<JurisdictionBlock> blocks = List.of();

    @PostConstruct
    void load() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) {
                log.warn("Missing industry regulation config: {} — regulatory references will be empty", CONFIG_PATH);
                return;
            }
            Map<String, Object> root = yaml.load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> regs = (Map<String, Object>) root.get("industry_regulations");
            if (regs == null || regs.isEmpty()) {
                log.warn("industry_regulations.yml has no 'industry_regulations' block");
                return;
            }

            List<JurisdictionBlock> out = new ArrayList<>();
            for (Map.Entry<String, Object> entry : regs.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) entry.getValue();
                List<String> keywords = stringListOrEmpty(raw.get("jurisdiction_keywords"));
                @SuppressWarnings("unchecked")
                Map<String, Object> industriesRaw = (Map<String, Object>) raw.get("industries");
                Map<String, String> industries = new LinkedHashMap<>();
                if (industriesRaw != null) {
                    for (Map.Entry<String, Object> ie : industriesRaw.entrySet()) {
                        industries.put(ie.getKey().toUpperCase(), String.valueOf(ie.getValue()));
                    }
                }
                out.add(new JurisdictionBlock(keywords, Collections.unmodifiableMap(industries)));
            }
            this.blocks = Collections.unmodifiableList(out);
            log.info("IndustryRegulationRegistry loaded {} jurisdiction blocks from {}", out.size(), CONFIG_PATH);
        } catch (IOException e) {
            log.error("Failed to load {}: {}", CONFIG_PATH, e.getMessage());
        }
    }

    /**
     * Look up the regulatory reference for a given jurisdiction and industry.
     * Returns empty string if no match is found.
     */
    public String getRegulatoryReference(String jurisdiction, String industry) {
        if (jurisdiction == null || jurisdiction.isBlank() || industry == null || industry.isBlank()) {
            return "";
        }
        String jurLower = jurisdiction.toLowerCase();
        String industryUpper = industry.toUpperCase();

        for (JurisdictionBlock block : blocks) {
            boolean matches = block.keywords().stream().anyMatch(jurLower::contains);
            if (matches) {
                return block.industries().getOrDefault(industryUpper, "");
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListOrEmpty(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object x : list) if (x != null) out.add(x.toString().toLowerCase());
            return Collections.unmodifiableList(out);
        }
        return List.of();
    }
}
