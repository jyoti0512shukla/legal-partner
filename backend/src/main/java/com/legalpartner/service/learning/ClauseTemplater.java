package com.legalpartner.service.learning;

import com.legalpartner.config.LegalVocabulary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a clause taken from one client's contract into a reusable firm clause:
 * the contract's party names become the configured party placeholders (resolved at
 * drafting time), and deal-specific values matching {@code vocabulary.yml entity_patterns}
 * become their fill-in placeholders. Deterministic; a lawyer reviews the result before
 * it is approved. Placeholders, corporate suffixes and generic words come from
 * {@code learning.yml templating}.
 */
public final class ClauseTemplater {

    private final String partyAPlaceholder;
    private final String partyBPlaceholder;
    private final String otherEntityPlaceholder;
    private final List<LegalVocabulary.EntityPattern> entityPatterns;
    private final Pattern corporateSuffix;
    private final Set<String> genericWords;

    public ClauseTemplater(String partyAPlaceholder, String partyBPlaceholder, String otherEntityPlaceholder,
                           List<LegalVocabulary.EntityPattern> entityPatterns,
                           Collection<String> corporateSuffixes, Set<String> genericWords) {
        this.partyAPlaceholder = partyAPlaceholder;
        this.partyBPlaceholder = partyBPlaceholder;
        this.otherEntityPlaceholder = otherEntityPlaceholder;
        this.entityPatterns = List.copyOf(entityPatterns);
        String alt = corporateSuffixes.stream()
                .map(s -> Pattern.quote(s.replaceAll("\\.$", "")) + "\\.?")
                .collect(Collectors.joining("|"));
        this.corporateSuffix = alt.isEmpty() ? null
                : Pattern.compile("(?i),?\\s+(?:" + alt + ")(?:\\s|,|$).*$");
        this.genericWords = genericWords.stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
    }

    public static ClauseTemplater from(LearningConfig c, LegalVocabulary vocabulary) {
        return new ClauseTemplater(c.getPartyAPlaceholder(), c.getPartyBPlaceholder(), c.getOtherEntityPlaceholder(),
                vocabulary.entityPatterns(), c.getCorporateSuffixes(), c.getGenericNameWords());
    }

    /**
     * @param partyA  Party A name in the source contract (nullable)
     * @param partyB  Party B name in the source contract (nullable)
     * @param otherEntities additional literal strings to blank out (e.g. from the anonymisation map)
     */
    public String templatize(String text, String partyA, String partyB, Collection<String> otherEntities) {
        if (text == null) return "";
        String out = text;
        out = replaceName(out, partyA, partyAPlaceholder);
        out = replaceName(out, partyB, partyBPlaceholder);
        // Every deal-specific value is templated, however small (min_digits only filters the leak check).
        for (LegalVocabulary.EntityPattern p : entityPatterns) {
            out = p.pattern().matcher(out).replaceAll(Matcher.quoteReplacement(p.placeholder()));
        }
        if (otherEntities != null) {
            List<String> entities = new ArrayList<>(otherEntities);
            entities.sort(Comparator.comparingInt(String::length).reversed());
            for (String e : entities) {
                if (e != null && e.trim().length() >= 3) {
                    out = out.replaceAll("(?i)" + Pattern.quote(e.trim()), Matcher.quoteReplacement(otherEntityPlaceholder));
                }
            }
        }
        return out.strip();
    }

    private String replaceName(String text, String name, String placeholder) {
        if (name == null || name.isBlank() || name.trim().length() < 3) return text;
        String n = name.trim();
        String out = text.replaceAll("(?i)" + Pattern.quote(n), Matcher.quoteReplacement(placeholder));
        // Also the short form without a corporate suffix ("Northwind Systems" for "Northwind Systems, Inc.")
        String core = corporateSuffix == null ? n : corporateSuffix.matcher(n).replaceAll("").trim();
        if (!core.equals(n) && core.length() >= 4) {
            out = out.replaceAll("(?i)\\b" + Pattern.quote(core) + "\\b", Matcher.quoteReplacement(placeholder));
        }
        // And the distinctive first word ("Northwind"), unless it is a generic word that would
        // also match ordinary contract language ("General", "National", "First" …).
        String first = core.split("\\s+")[0].replaceAll("[^\\p{L}\\p{N}-]", "");
        if (first.length() >= 4 && !first.equalsIgnoreCase(core) && !genericWords.contains(first.toLowerCase())) {
            out = out.replaceAll("\\b" + Pattern.quote(first) + "\\b", Matcher.quoteReplacement(placeholder));
        }
        return out;
    }
}
