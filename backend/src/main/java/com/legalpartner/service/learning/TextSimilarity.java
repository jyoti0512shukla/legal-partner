package com.legalpartner.service.learning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic text similarity used by the learning loop (no model, no network). */
public final class TextSimilarity {

    private TextSimilarity() {}

    /** Lower-cased word tokens (letters, digits, apostrophes); punctuation dropped. */
    public static List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** k-word shingles; a text shorter than k yields one shingle of all its tokens. */
    public static Set<String> shingles(String text, int k) {
        List<String> t = tokens(text);
        Set<String> out = new HashSet<>();
        if (t.isEmpty()) return out;
        if (t.size() < k) {
            out.add(String.join(" ", t));
            return out;
        }
        for (int i = 0; i + k <= t.size(); i++) out.add(String.join(" ", t.subList(i, i + k)));
        return out;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        int union = a.size() + b.size() - inter.size();
        return union == 0 ? 0.0 : (double) inter.size() / union;
    }

    /** Jaccard over 2-word shingles — used to decide whether two short rules say the same thing. */
    public static double ruleSimilarity(String a, String b) {
        return jaccard(shingles(a, 2), shingles(b, 2));
    }

    /**
     * Word-level edit ratio: Levenshtein distance over tokens / max(token counts).
     * 0 = identical wording, 1 = completely rewritten. Punctuation and case are ignored,
     * so reformatting alone does not count as a lawyer edit.
     */
    public static double editRatio(String before, String after) {
        List<String> a = tokens(before);
        List<String> b = tokens(after);
        int n = a.size(), m = b.size();
        if (n == 0 && m == 0) return 0.0;
        if (n == 0 || m == 0) return 1.0;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            String ai = a.get(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ai.equals(b.get(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return (double) prev[m] / Math.max(n, m);
    }

    /** Normalised title for aligning articles ("ARTICLE 3 — Limitation of Liability" → "limitation of liability"). */
    public static String normTitle(String title) {
        if (title == null) return "";
        return String.join(" ", tokens(title.replaceFirst("(?i)^\\s*article\\s+\\d+\\s*[—–-]?", "")));
    }

    static String[] sortedCopy(Set<String> s) {
        String[] a = s.toArray(new String[0]);
        Arrays.sort(a);
        return a;
    }
}
