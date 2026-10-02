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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drafting defaults and last-resort placeholder cleanup, from
 * {@code config/drafting_defaults.yml}. See that file for the policy note on defaults.
 */
@Component
@Slf4j
public class DraftingDefaults {

    private static final String CONFIG_PATH = "config/drafting_defaults.yml";
    private static final Pattern VAR = Pattern.compile("\\{\\{([A-Z_]+)}}");

    public record PlaceholderRule(Pattern pattern, String replacement) {}

    private Map<String, String> formDefaults = Map.of();
    private Map<String, String> promptDefaults = Map.of();
    private String agreementRefPrefix = "AGR";
    private String styleFingerprint = "";
    private List<PlaceholderRule> placeholderRules = List.of();

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) throw new IllegalStateException("Missing " + CONFIG_PATH);
            Map<String, Object> root = new Yaml().load(in);
            this.formDefaults = stringMap(root.get("form_defaults"));
            this.promptDefaults = stringMap(root.get("prompt_defaults"));
            this.agreementRefPrefix = String.valueOf(root.getOrDefault("agreement_ref_prefix", "AGR"));
            this.styleFingerprint = String.valueOf(root.getOrDefault("style_fingerprint", "")).strip();
            List<PlaceholderRule> rules = new ArrayList<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) root.getOrDefault("placeholder_rules", List.of())) {
                rules.add(new PlaceholderRule(Pattern.compile(String.valueOf(r.get("pattern"))),
                        String.valueOf(r.get("replacement"))));
            }
            this.placeholderRules = Collections.unmodifiableList(rules);
            log.info("DraftingDefaults: {} form defaults, {} placeholder rules", formDefaults.size(), rules.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    /** Default for a template header variable (e.g. NOTICE_DAYS). Fails loudly if not configured. */
    public String form(String key) {
        String v = formDefaults.get(key);
        if (v == null) throw new IllegalStateException(CONFIG_PATH + ": form_defaults." + key + " is not defined");
        return v;
    }

    /** Default substituted into prompts when a request field is blank. */
    public String prompt(String key) {
        String v = promptDefaults.get(key);
        if (v == null) throw new IllegalStateException(CONFIG_PATH + ": prompt_defaults." + key + " is not defined");
        return v;
    }

    public String agreementRefPrefix() { return agreementRefPrefix; }

    /** Style directive with {{VARS}} substituted (e.g. DRAFTING_REGISTER). */
    public String styleFingerprint(Map<String, String> vars) {
        return substitute(styleFingerprint, vars);
    }

    /** Apply every placeholder rule in order; replacements may use {{VARS}}. */
    public String applyPlaceholderRules(String text, Map<String, String> vars) {
        String out = text;
        for (PlaceholderRule r : placeholderRules) {
            out = r.pattern().matcher(out).replaceAll(Matcher.quoteReplacement(substitute(r.replacement(), vars)));
        }
        return out;
    }

    public List<PlaceholderRule> placeholderRules() { return placeholderRules; }

    static String substitute(String template, Map<String, String> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return Collections.unmodifiableMap(out);
    }
}
