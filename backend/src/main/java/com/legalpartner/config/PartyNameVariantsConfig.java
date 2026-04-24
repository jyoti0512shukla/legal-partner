package com.legalpartner.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Loads generic party name variants from
 * {@code resources/config/party_name_variants.yml}.
 *
 * Used by DraftService.enforcePartyRoles() to replace generic terms
 * (e.g. "the Service Provider") with contract-type-specific role names
 * (e.g. "Licensor"). Adding a new variant requires only a YAML change.
 */
@Component
@Slf4j
public class PartyNameVariantsConfig {

    private static final String CONFIG_PATH = "config/party_name_variants.yml";

    private List<String> partyAVariants = List.of();
    private List<String> partyBVariants = List.of();

    @PostConstruct
    void load() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) {
                log.warn("Missing party name variants config: {} — using built-in defaults", CONFIG_PATH);
                loadDefaults();
                return;
            }
            Map<String, Object> root = yaml.load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> variants = (Map<String, Object>) root.get("party_name_variants");
            if (variants == null || variants.isEmpty()) {
                log.warn("party_name_variants.yml has no 'party_name_variants' block — using defaults");
                loadDefaults();
                return;
            }
            this.partyAVariants = Collections.unmodifiableList(stringListOrEmpty(variants.get("partyA")));
            this.partyBVariants = Collections.unmodifiableList(stringListOrEmpty(variants.get("partyB")));
            log.info("PartyNameVariantsConfig loaded {} partyA + {} partyB variants from {}",
                    partyAVariants.size(), partyBVariants.size(), CONFIG_PATH);
        } catch (IOException e) {
            log.error("Failed to load {}: {} — using defaults", CONFIG_PATH, e.getMessage());
            loadDefaults();
        }
    }

    private void loadDefaults() {
        this.partyAVariants = List.of(
                "the Service Provider", "Service Provider",
                "the Vendor", "Vendor",
                "the Company", "Company",
                "the Supplier", "Supplier",
                "the Provider", "Provider",
                "the Employer", "Employer"
        );
        this.partyBVariants = List.of(
                "the Client", "Client",
                "the Enterprise", "Enterprise",
                "the Buyer", "Buyer",
                "the Customer", "Customer",
                "the Employee", "Employee"
        );
    }

    public List<String> partyAVariants() { return partyAVariants; }
    public List<String> partyBVariants() { return partyBVariants; }

    @SuppressWarnings("unchecked")
    private static List<String> stringListOrEmpty(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object x : list) if (x != null) out.add(x.toString());
            return out;
        }
        return new ArrayList<>();
    }
}
