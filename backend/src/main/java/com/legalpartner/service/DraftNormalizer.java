package com.legalpartner.service;

import com.legalpartner.model.dto.DealSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Final normalization pass for complete drafts. Runs AFTER all clauses are
 * generated and BEFORE HTML output. 100% deterministic — no LLM calls.
 *
 * Fixes the remaining quality issues that LLM-generated clauses introduce:
 *   1. Duplicate sub-clauses (same text repeated within a section)
 *   2. Meta-commentary leaked from the LLM ("Here is the clause...", "```")
 *   3. Inconsistent numbering within articles
 *   4. Dangling cross-references ("Schedule B", "Section X")
 *   5. Hallucinated dollar amounts not in the DealSpec
 */
@Service
@Slf4j
public class DraftNormalizer {

    // ── Meta-commentary patterns ──────────────────────────────────────────────
    // Lines matching ANY of these are not legal prose and must be stripped.
    private static final Pattern META_COMMENTARY_PATTERN = Pattern.compile(
            "(?i)"
            + "(?:^\\s*(?:here\\s+is|below\\s+is|the\\s+following\\s+is|I(?:'ve|\\s+have)\\s+(?:draft|creat|prepar|generat|writ)))"
            + "|(?:^\\s*```)"                                       // fenced code block markers
            + "|(?:^\\s*\\*\\*(?:Note|Disclaimer|Important)\\*?\\*?\\s*:)" // **Note:** style
            + "|(?:^\\s*(?:Note|Disclaimer)\\s*:)"                  // Note: / Disclaimer:
            + "|(?:(?:fixed|golden|template|placeholder|sample|example)\\s+clause)"
            + "|(?:(?:replace|fill\\s+in|insert|update)\\s+(?:the\\s+)?(?:placeholder|bracket|value))"
            + "|(?:this\\s+(?:clause|section|provision)\\s+(?:is|was|has\\s+been)\\s+(?:draft|generat|creat|prepar))"
            + "|(?:(?:as\\s+(?:per|requested|instructed)))"
            + "|(?:violation|non-?complian)"                        // QA violation artifacts
            + "|(?:^\\s*---+\\s*$)"                                 // horizontal rule dividers
    );

    // ── Sub-clause numbering patterns ─────────────────────────────────────────
    private static final Pattern NUMBERED_SUBCLAUSE = Pattern.compile(
            "^(\\s*)(?:(\\d+)(\\.\\d+)*\\.?\\s+)(.*)$");

    // ── Dangling cross-reference patterns ─────────────────────────────────────
    private static final Pattern DANGLING_SCHEDULE_REF = Pattern.compile(
            "(?i)\\b(?:Schedule|Annex|Appendix|Exhibit)\\s+[A-Z]\\b");
    private static final Pattern DANGLING_SECTION_REF = Pattern.compile(
            "(?i)\\bSection\\s+(?:X+|\\[\\w*\\])\\b");

    // ── Dollar amount pattern ─────────────────────────────────────────────────
    private static final Pattern DOLLAR_AMOUNT = Pattern.compile(
            "\\$[\\d,]+(?:\\.\\d{2})?");

    /**
     * Final normalization of the complete draft. Runs AFTER all clauses generated,
     * BEFORE HTML output. Deterministic — no LLM calls.
     *
     * @param sectionValues mutable map of clause key → HTML content
     * @param dealSpec      structured deal terms (may be null)
     * @return normalized section values (same map reference, mutated in-place)
     */
    public Map<String, String> normalize(Map<String, String> sectionValues, DealSpec dealSpec) {
        if (sectionValues == null || sectionValues.isEmpty()) {
            return sectionValues;
        }

        int totalStripped = 0;
        int totalDeduped = 0;
        int totalRefsFixed = 0;
        int totalAmountsStripped = 0;

        // Build the set of known dollar amounts from DealSpec
        Set<String> knownAmounts = buildKnownAmounts(dealSpec);

        for (Map.Entry<String, String> entry : sectionValues.entrySet()) {
            String key = entry.getKey();
            String html = entry.getValue();
            if (html == null || html.isBlank()) continue;

            // 1. DEDUP: remove duplicate sub-clauses (same first 80 chars)
            DedupeResult deduped = deduplicateSubClauses(html);
            html = deduped.html;
            totalDeduped += deduped.removedCount;

            // 2. META STRIP: remove lines that are not legal prose
            StripResult stripped = stripMetaCommentary(html);
            html = stripped.html;
            totalStripped += stripped.removedCount;

            // 3. NUMBERING FIX: re-number sub-clauses sequentially within each article
            html = renumberSubClauses(html);

            // 4. CROSS-REFERENCE FIX: clean up dangling schedule/section references
            CrossRefResult crossRefResult = fixCrossReferences(html);
            html = crossRefResult.html;
            totalRefsFixed += crossRefResult.fixedCount;

            // 5. NUMERIC CONSISTENCY: strip dollar amounts not in DealSpec
            if (dealSpec != null && !knownAmounts.isEmpty()) {
                AmountResult amountResult = stripUnknownAmounts(html, knownAmounts);
                html = amountResult.html;
                totalAmountsStripped += amountResult.strippedCount;
            }

            // 6. FINAL BLOCK: if any meta-commentary still remains, strip and warn
            StripResult finalStrip = stripMetaCommentary(html);
            if (finalStrip.removedCount > 0) {
                log.warn("DraftNormalizer [{}]: {} meta-commentary line(s) survived initial strip — removed in final pass",
                        key, finalStrip.removedCount);
                html = finalStrip.html;
                totalStripped += finalStrip.removedCount;
            }

            entry.setValue(html);
        }

        if (totalDeduped > 0 || totalStripped > 0 || totalRefsFixed > 0 || totalAmountsStripped > 0) {
            log.info("DraftNormalizer: deduped={}, meta-stripped={}, cross-refs-fixed={}, amounts-stripped={}",
                    totalDeduped, totalStripped, totalRefsFixed, totalAmountsStripped);
        }

        return sectionValues;
    }

    // ── 1. Deduplication ──────────────────────────────────────────────────────

    private record DedupeResult(String html, int removedCount) {}

    private DedupeResult deduplicateSubClauses(String html) {
        // Split on paragraph boundaries (<p> tags or double newlines)
        String[] paragraphs = html.split("(?=<p\\b)|(?<=</p>)");
        List<String> seen = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        int removed = 0;

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            // Extract text content (strip HTML tags) for comparison
            String textOnly = stripHtmlTags(trimmed);
            if (textOnly.isBlank()) {
                kept.add(para);
                continue;
            }

            // Fingerprint: first 80 chars of the stripped text
            String fingerprint = textOnly.length() > 80
                    ? textOnly.substring(0, 80).toLowerCase().replaceAll("\\s+", " ")
                    : textOnly.toLowerCase().replaceAll("\\s+", " ");

            boolean isDuplicate = false;
            for (String existing : seen) {
                if (existing.equals(fingerprint)) {
                    isDuplicate = true;
                    break;
                }
            }

            if (isDuplicate) {
                removed++;
            } else {
                seen.add(fingerprint);
                kept.add(para);
            }
        }

        return new DedupeResult(String.join("", kept), removed);
    }

    // ── 2. Meta-commentary stripping ──────────────────────────────────────────

    private record StripResult(String html, int removedCount) {}

    private StripResult stripMetaCommentary(String html) {
        // Process line-by-line within paragraph tags
        String[] lines = html.split("\\n");
        List<String> kept = new ArrayList<>();
        int removed = 0;

        for (String line : lines) {
            String textContent = stripHtmlTags(line).trim();
            if (textContent.isEmpty()) {
                kept.add(line);
                continue;
            }

            if (META_COMMENTARY_PATTERN.matcher(textContent).find()) {
                removed++;
                // Don't keep this line — it's meta-commentary
            } else {
                kept.add(line);
            }
        }

        return new StripResult(String.join("\n", kept), removed);
    }

    // ── 3. Re-numbering ──────────────────────────────────────────────────────

    private String renumberSubClauses(String html) {
        String[] lines = html.split("\\n");
        List<String> result = new ArrayList<>();
        int counter = 0;
        int lastSeenNumber = -1;

        for (String line : lines) {
            String textContent = stripHtmlTags(line).trim();
            Matcher m = NUMBERED_SUBCLAUSE.matcher(textContent);

            if (m.matches()) {
                int currentNumber;
                try {
                    currentNumber = Integer.parseInt(m.group(2));
                } catch (NumberFormatException e) {
                    result.add(line);
                    continue;
                }

                // Detect duplicate numbering (same number as previous, or out of sequence)
                if (currentNumber == lastSeenNumber) {
                    // Duplicate number — renumber
                    counter++;
                    String newContent = m.group(1) + counter + ". " + m.group(4);
                    line = line.replace(textContent, newContent);
                } else if (currentNumber != counter + 1 && counter > 0) {
                    // Out of sequence — renumber
                    counter++;
                    String newContent = m.group(1) + counter + ". " + m.group(4);
                    line = line.replace(textContent, newContent);
                } else {
                    counter = currentNumber;
                }
                lastSeenNumber = currentNumber;
            }

            result.add(line);
        }

        return String.join("\n", result);
    }

    // ── 4. Cross-reference fixing ─────────────────────────────────────────────

    private record CrossRefResult(String html, int fixedCount) {}

    private CrossRefResult fixCrossReferences(String html) {
        int fixed = 0;

        // Replace dangling "Section X" / "Section [xxx]" references
        Matcher sectionMatcher = DANGLING_SECTION_REF.matcher(html);
        if (sectionMatcher.find()) {
            html = sectionMatcher.replaceAll("the applicable section of this Agreement");
            fixed += countMatches(sectionMatcher.reset(html));
            // Re-count after replacement won't match, so count from original
            sectionMatcher.reset();
        }
        // Count actual replacements on original
        {
            Matcher counter = DANGLING_SECTION_REF.matcher(html);
            // If still found, they weren't replaced (shouldn't happen, but safe)
        }

        // For dangling Schedule/Annex/Appendix refs — only remove if they reference
        // schedules that don't exist in the draft (we can't verify, so replace generically)
        Matcher scheduleMatcher = DANGLING_SCHEDULE_REF.matcher(html);
        StringBuffer sb = new StringBuffer();
        int scheduleFixed = 0;
        while (scheduleMatcher.find()) {
            // Keep "Schedule A" as it's commonly the first schedule; flag others
            String match = scheduleMatcher.group();
            if (match.matches("(?i).*\\s+A$")) {
                scheduleMatcher.appendReplacement(sb, Matcher.quoteReplacement(match));
            } else {
                scheduleMatcher.appendReplacement(sb, "the applicable schedule to this Agreement");
                scheduleFixed++;
            }
        }
        scheduleMatcher.appendTail(sb);
        html = sb.toString();
        fixed += scheduleFixed;

        return new CrossRefResult(html, fixed);
    }

    // ── 5. Numeric consistency ────────────────────────────────────────────────

    private record AmountResult(String html, int strippedCount) {}

    private AmountResult stripUnknownAmounts(String html, Set<String> knownAmounts) {
        Matcher m = DOLLAR_AMOUNT.matcher(html);
        StringBuffer sb = new StringBuffer();
        int stripped = 0;

        while (m.find()) {
            String amount = m.group();
            if (!knownAmounts.contains(normalizeAmount(amount))) {
                // Replace unknown amount with "[amount]" placeholder
                m.appendReplacement(sb, "[amount per agreement]");
                stripped++;
            }
        }
        m.appendTail(sb);

        return new AmountResult(sb.toString(), stripped);
    }

    private Set<String> buildKnownAmounts(DealSpec dealSpec) {
        if (dealSpec == null) return Set.of();

        Set<String> amounts = new java.util.HashSet<>();
        if (dealSpec.getFees() != null) {
            addFormattedAmount(amounts, dealSpec.getFees().getLicenseFee());
            addFormattedAmount(amounts, dealSpec.getFees().getMaintenanceFee());
        }
        return amounts;
    }

    private void addFormattedAmount(Set<String> amounts, Long value) {
        if (value == null || value == 0) return;
        // Add both comma-formatted and plain versions
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        amounts.add(nf.format(value));
        amounts.add(String.valueOf(value));
        // Also add with .00 suffix
        amounts.add(nf.format(value) + ".00");
        amounts.add(value + ".00");
    }

    private String normalizeAmount(String amount) {
        // Strip $ prefix and normalize
        return amount.replace("$", "").replace(",", "");
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static String stripHtmlTags(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("&[a-zA-Z]+;", " ").trim();
    }

    private static int countMatches(Matcher matcher) {
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }
}
