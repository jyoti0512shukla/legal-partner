package com.legalpartner.service.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-based clause inventory for contracts we did not draft (uploads, imports).
 *
 * For our own drafts, review uses the draft manifest instead (exact sections, no
 * guessing) — see {@link DraftManifest}. This class is the fallback path.
 *
 * Heading-first matching: keywords are matched against section heading lines
 * (ARTICLE n / Section n / Clause n) before falling back to earliest occurrence
 * anywhere in the text. Without this, "liability" matches its first mention (often
 * in DEFINITIONS or WARRANTIES) and the questions run against the wrong section.
 */
public final class ClauseInventory {

    private ClauseInventory() {}


    private static final Pattern HEADING_LINE = Pattern.compile(
            "(?im)^[ \\t]*(?:ARTICLE|Article|SECTION|Section|Clause|CLAUSE)\\s+\\d+[^\\n]*");
    private static final Pattern HEADING_START = Pattern.compile(
            "(?im)^[ \\t]*(?:ARTICLE|Article|SECTION|Section|Clause|CLAUSE)\\s+\\d+");

    /**
     * Clause key → extracted clause text (only clauses with > 100 chars of text).
     *
     * @param keywords clause key → keywords, from risk_questions.yml {@code clause_keywords}
     */
    public static Map<String, String> inventory(String fullText, Map<String, List<String>> keywords,
                                                int sectionCapChars) {
        Map<String, String> result = new LinkedHashMap<>();
        if (fullText == null || fullText.isBlank()) return result;
        String lowerText = fullText.toLowerCase();
        List<int[]> headings = findSectionHeadings(fullText);

        for (Map.Entry<String, List<String>> entry : keywords.entrySet()) {
            String clauseType = entry.getKey();
            List<String> clauseKeywords = entry.getValue();

            // Pass A: match keywords against heading lines (first heading in doc order wins)
            int matchIdx = -1;
            for (int[] h : headings) {
                String headingLine = lowerText.substring(h[0], h[1]);
                for (String kw : clauseKeywords) {
                    if (indexOfKeyword(headingLine, kw, 0) >= 0) { matchIdx = h[0]; break; }
                }
                if (matchIdx >= 0) break;
            }

            // Pass B: fall back to earliest occurrence anywhere in the text
            if (matchIdx < 0) {
                for (String kw : clauseKeywords) {
                    int idx = indexOfKeyword(lowerText, kw, 0);
                    if (idx >= 0 && (matchIdx < 0 || idx < matchIdx)) matchIdx = idx;
                }
            }

            if (matchIdx >= 0) {
                String extracted = extractClauseText(fullText, matchIdx, sectionCapChars);
                if (extracted.length() > 100) {
                    result.put(clauseType, extracted);
                }
            }
        }
        return result;
    }

    /**
     * Keyword search. Short keywords (acronyms such as "nda", "sla") must match as whole
     * words, otherwise "nda" hits "standard"/"amendment" and "sla" hits "legislation".
     */
    static int indexOfKeyword(String lowerText, String kw, int from) {
        if (kw.length() > 4) return lowerText.indexOf(kw, from);
        Matcher m = Pattern.compile("\\b" + Pattern.quote(kw) + "\\b").matcher(lowerText);
        return m.find(from) ? m.start() : -1;
    }

    /** [startOfHeading, endOfLine] pairs for section heading lines, in document order. */
    public static List<int[]> findSectionHeadings(String fullText) {
        Matcher m = HEADING_LINE.matcher(fullText);
        List<int[]> headings = new ArrayList<>();
        while (m.find()) headings.add(new int[]{m.start(), m.end()});
        return headings;
    }

    /** Text from the heading that contains {@code startIdx} up to the next heading (capped). */
    public static String extractClauseText(String fullText, int startIdx, int sectionCapChars) {
        Matcher m = HEADING_START.matcher(fullText);
        int sectionStart = startIdx;
        int sectionEnd = fullText.length();

        List<Integer> headerPositions = new ArrayList<>();
        while (m.find()) headerPositions.add(m.start());

        for (int pos : headerPositions) {
            if (pos <= startIdx) {
                sectionStart = pos;
            } else {
                sectionEnd = pos;
                break;
            }
        }

        int end = Math.min(sectionEnd, sectionStart + sectionCapChars);
        return fullText.substring(sectionStart, end).trim();
    }
}
