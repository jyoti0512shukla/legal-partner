package com.legalpartner.service.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits a contract's plain text into "ARTICLE n — TITLE" sections (the drafter's heading format). */
public final class ArticleSplitter {

    public record Article(int number, String title, String body) {}

    private static final Pattern HEADING = Pattern.compile("(?im)^[ \\t]*ARTICLE\\s+(\\d+)\\s*[—–:.-]+\\s*(.+?)\\s*$");

    private ArticleSplitter() {}

    public static List<Article> split(String text) {
        List<Article> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        Matcher m = HEADING.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String[]> heads = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            heads.add(new String[]{m.group(1), m.group(2)});
        }
        for (int i = 0; i < spans.size(); i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            out.add(new Article(Integer.parseInt(heads.get(i)[0]), heads.get(i)[1].trim(),
                    text.substring(spans.get(i)[1], end).strip()));
        }
        return out;
    }
}
