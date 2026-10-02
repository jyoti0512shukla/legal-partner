package com.legalpartner.rag;

import java.util.regex.Pattern;

/**
 * HTML → plain text that preserves document structure.
 *
 * Block-level elements (headings, paragraphs, list items, table rows, line breaks)
 * become line breaks, so line-anchored patterns such as {@code ^ARTICLE 3} keep
 * working on the output. Inline whitespace is collapsed; blank lines are capped at one.
 */
public final class HtmlText {

    private HtmlText() {}

    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    /** Opening or closing block-level tags → newline. */
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?i)</?(p|div|h[1-6]|li|ul|ol|tr|table|thead|tbody|section|article|header|footer|blockquote|pre|hr)(\\s[^>]*)?/?>");
    private static final Pattern CELL_END = Pattern.compile("(?i)</t[dh]>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern INLINE_WS = Pattern.compile("[ \\t\\x0B\\f\\u00A0]+");
    private static final Pattern SPACE_AROUND_NL = Pattern.compile(" *\\n *");
    private static final Pattern MANY_NL = Pattern.compile("\\n{3,}");

    public static String toPlainText(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = SCRIPT_STYLE.matcher(html).replaceAll("");
        s = COMMENT.matcher(s).replaceAll("");
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = BR.matcher(s).replaceAll("\n");
        s = CELL_END.matcher(s).replaceAll(" \t ");
        s = BLOCK_TAG.matcher(s).replaceAll("\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = INLINE_WS.matcher(s).replaceAll(" ");
        s = SPACE_AROUND_NL.matcher(s).replaceAll("\n");
        s = MANY_NL.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    static String decodeEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&mdash;", "—").replace("&#8212;", "—").replace("&#x2014;", "—")
                .replace("&ndash;", "–").replace("&#8211;", "–")
                .replace("&ldquo;", "“").replace("&rdquo;", "”")
                .replace("&lsquo;", "‘").replace("&rsquo;", "’")
                .replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'")
                .replace("&sect;", "§").replace("&#167;", "§")
                .replace("&#x23F3;", "")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
