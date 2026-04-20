package com.legalpartner.service;

import lombok.extern.slf4j.Slf4j;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts draft HTML to DOCX using docx4j.
 * Parses the HTML structure and builds Word paragraphs directly —
 * no XHTML importer dependency needed.
 */
@Service
@Slf4j
public class HtmlToDocxConverter {

    private static final Pattern H1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern H2 = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TAG = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DIV_BODY = Pattern.compile("<div class=\"article-body\">(.*?)</div>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STRONG = Pattern.compile("<strong>(.*?)</strong>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern EM = Pattern.compile("<em>(.*?)</em>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DETAILS = Pattern.compile("<details[^>]*>.*?</details>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HR_DASHED = Pattern.compile("<hr[^>]*style=\"[^\"]*dashed[^\"]*\"[^>]*/?>", Pattern.CASE_INSENSITIVE);

    private final ObjectFactory factory = new ObjectFactory();

    public byte[] convert(String html) {
        try {
            WordprocessingMLPackage wordPackage = WordprocessingMLPackage.createPackage();
            var body = wordPackage.getMainDocumentPart();

            // Remove draft parameters section (not for Word doc)
            String cleaned = HR_DASHED.matcher(html).replaceAll("");
            cleaned = DETAILS.matcher(cleaned).replaceAll("");

            // Strip style block
            cleaned = cleaned.replaceAll("(?s)<style[^>]*>.*?</style>", "");

            // Extract and process content between <body> and </body>
            int bodyStart = cleaned.toLowerCase().indexOf("<body");
            int bodyEnd = cleaned.toLowerCase().indexOf("</body>");
            if (bodyStart >= 0 && bodyEnd > bodyStart) {
                bodyStart = cleaned.indexOf('>', bodyStart) + 1;
                cleaned = cleaned.substring(bodyStart, bodyEnd);
            }

            // Process elements in order
            String[] blocks = cleaned.split("(?=<h[12][^>]*>)|(?=<p[^>]*>)|(?=<div )");
            for (String block : blocks) {
                block = block.trim();
                if (block.isEmpty()) continue;

                Matcher h1 = H1.matcher(block);
                Matcher h2 = H2.matcher(block);

                if (h1.find()) {
                    addHeading(body, stripTags(h1.group(1)), "Title");
                } else if (h2.find()) {
                    addHeading(body, stripTags(h2.group(1)), "Heading2");
                } else if (block.startsWith("<div class=\"article-body\">")) {
                    // Process paragraphs inside article body
                    Matcher p = P_TAG.matcher(block);
                    while (p.find()) {
                        String content = p.group(1).trim();
                        if (content.isEmpty()) continue;
                        addParagraph(body, content);
                    }
                } else {
                    Matcher p = P_TAG.matcher(block);
                    while (p.find()) {
                        String content = p.group(1).trim();
                        if (content.isEmpty()) continue;
                        addParagraph(body, content);
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wordPackage.save(out);
            log.info("HTML→DOCX: {} chars HTML → {} bytes DOCX", html.length(), out.size());
            return out.toByteArray();

        } catch (Exception e) {
            log.error("HTML→DOCX failed: {}", e.getMessage(), e);
            throw new RuntimeException("DOCX conversion failed: " + e.getMessage(), e);
        }
    }

    private void addHeading(org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart body,
                            String text, String style) {
        P p = factory.createP();
        PPr ppr = factory.createPPr();
        PPrBase.PStyle pStyle = factory.createPPrBasePStyle();
        pStyle.setVal(style);
        ppr.setPStyle(pStyle);
        if ("Title".equals(style)) {
            Jc jc = factory.createJc();
            jc.setVal(JcEnumeration.CENTER);
            ppr.setJc(jc);
        }
        p.setPPr(ppr);
        addFormattedRuns(p, text);
        body.getContent().add(p);
    }

    private void addParagraph(org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart body,
                              String htmlContent) {
        P p = factory.createP();

        // Check if this is a sub-clause (indented)
        if (htmlContent.contains("clause-sub") || htmlContent.matches("^\\s*<strong>\\d+\\..*")) {
            PPr ppr = factory.createPPr();
            PPrBase.Ind ind = factory.createPPrBaseInd();
            ind.setLeft(BigInteger.valueOf(720)); // 0.5 inch indent
            ppr.setInd(ind);
            p.setPPr(ppr);
        }

        String plain = stripTags(htmlContent);
        addFormattedRuns(p, plain);
        body.getContent().add(p);
    }

    /** Add runs to a paragraph, handling bold text from <strong> tags. */
    private void addFormattedRuns(P p, String text) {
        // Split on bold markers and create runs
        String[] parts = text.split("(?=\\*\\*)|(?<=\\*\\*)");
        boolean bold = false;
        for (String part : parts) {
            if ("**".equals(part)) { bold = !bold; continue; }
            if (part.isEmpty()) continue;
            R run = factory.createR();
            if (bold) {
                RPr rpr = factory.createRPr();
                BooleanDefaultTrue b = factory.createBooleanDefaultTrue();
                rpr.setB(b);
                run.setRPr(rpr);
            }
            Text t = factory.createText();
            t.setValue(part);
            t.setSpace("preserve");
            run.getContent().add(t);
            p.getContent().add(run);
        }
        // If no parts were processed, add the full text as a single run
        if (p.getContent().isEmpty()) {
            R run = factory.createR();
            Text t = factory.createText();
            t.setValue(text);
            t.setSpace("preserve");
            run.getContent().add(t);
            p.getContent().add(run);
        }
    }

    private String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("&#160;", " ")
                   .replaceAll("&#x23F3;", "")
                   .replaceAll("\\s{2,}", " ")
                   .trim();
    }
}
