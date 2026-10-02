package com.legalpartner.rag;

import com.legalpartner.service.review.ClauseInventory;
import com.legalpartner.testsupport.ConfigFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTextTest {

    /** Shape of a generated draft (DraftService.buildDynamicHtml). */
    private static final String DRAFT_HTML = """
            <html><head><style>h2 { color: red; }</style></head><body>
            <h1>SOFTWARE LICENSE AGREEMENT</h1>
            <h2>ARTICLE 1 — DEFINITIONS</h2>
            <div class="article-body"><p class="clause-sub"><strong>1.</strong> &ldquo;Software&rdquo; means the product.</p></div>
            <h2>ARTICLE 2 — LIMITATION OF LIABILITY</h2>
            <div class="article-body"><p class="clause-sub"><strong>1.</strong> Aggregate liability shall not exceed fees paid in the prior twelve (12) months.</p>
            <p class="clause-sub"><strong>2.</strong> Neither party is liable for indirect or consequential damages.</p></div>
            <h2>ARTICLE 3 — TERMINATION</h2>
            <div class="article-body"><p>Either party may terminate on thirty (30) days&rsquo; written notice for material breach not cured.</p></div>
            </body></html>
            """;

    @Test
    void keepsBlockBoundariesAsNewlines() {
        String text = HtmlText.toPlainText(DRAFT_HTML);
        assertThat(text).contains("\nARTICLE 2 — LIMITATION OF LIABILITY\n");
        assertThat(text.lines().filter(l -> l.startsWith("ARTICLE ")).count()).isEqualTo(3);
        assertThat(text).doesNotContain("<").doesNotContain("color: red");
    }

    @Test
    void decodesEntities() {
        String text = HtmlText.toPlainText("<p>A &amp; B&rsquo;s &ldquo;Software&rdquo;&nbsp;here</p>");
        assertThat(text).isEqualTo("A & B’s “Software” here");
    }

    @Test
    void draftHeadingsAreFoundAfterConversion() {
        // Regression: the old converter collapsed all whitespace, so no heading was found
        // and clause questions ran against the wrong text.
        String text = HtmlText.toPlainText(DRAFT_HTML);
        assertThat(ClauseInventory.findSectionHeadings(text)).hasSize(3);

        var inventory = ClauseInventory.inventory(text, ConfigFixtures.riskQuestions().getClauseKeywords(), 4000);
        assertThat(inventory.get("LIABILITY"))
                .startsWith("ARTICLE 2")
                .contains("consequential")
                .doesNotContain("TERMINATION");
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(HtmlText.toPlainText(null)).isEmpty();
        assertThat(HtmlText.toPlainText("")).isEmpty();
    }
}
