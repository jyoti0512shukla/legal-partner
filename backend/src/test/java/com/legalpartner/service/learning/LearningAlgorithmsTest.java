package com.legalpartner.service.learning;

import com.legalpartner.testsupport.ConfigFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class LearningAlgorithmsTest {

    private static final LearningConfig CONFIG = ConfigFixtures.learning();
    private static final MinHasher MINHASH = MinHasher.from(CONFIG);
    private static final ClauseTemplater TEMPLATER = ClauseTemplater.from(CONFIG, ConfigFixtures.vocabulary());

    private static final String CAP = "The aggregate liability of each Party arising out of or relating to this Agreement "
            + "shall not exceed the total fees paid or payable by the Customer in the twelve months preceding the claim, "
            + "and neither Party shall be liable for any indirect, incidental, special or consequential damages.";

    // ── edit ratio ─────────────────────────────────────────────────────────────

    @Test
    void editRatioIgnoresFormattingAndMeasuresRewrites() {
        assertThat(TextSimilarity.editRatio(CAP, CAP.toUpperCase().replace(",", ""))).isZero();
        double small = TextSimilarity.editRatio(CAP, CAP.replace("twelve months", "twenty-four months"));
        assertThat(small).isGreaterThan(0).isLessThan(0.1);
        assertThat(TextSimilarity.editRatio(CAP, "Liability is unlimited.")).isGreaterThan(0.9);
        assertThat(TextSimilarity.editRatio("", "")).isZero();
        assertThat(TextSimilarity.editRatio("", "x")).isEqualTo(1.0);
    }

    @Test
    void ruleSimilarityRecognisesParaphrasedRules() {
        assertThat(TextSimilarity.ruleSimilarity(
                "State the liability cap as a multiple of fees paid in the prior 12 months",
                "State the liability cap as a multiple of the fees paid in the prior twelve months")).isGreaterThan(0.5);
        assertThat(TextSimilarity.ruleSimilarity(
                "Add a data breach carve-out to the cap", "Use Delaware courts for disputes")).isLessThan(0.1);
    }

    // ── MinHash / LSH clustering ───────────────────────────────────────────────

    @Test
    void minhashEstimatesJaccard() {
        int[] a = MINHASH.signature(CAP);
        int[] b = MINHASH.signature(CAP.replace("twelve months", "twenty-four months"));
        double truth = TextSimilarity.jaccard(TextSimilarity.shingles(CAP, CONFIG.getMinhashShingleWords()),
                TextSimilarity.shingles(CAP.replace("twelve months", "twenty-four months"), CONFIG.getMinhashShingleWords()));
        assertThat(MinHasher.estimate(a, b)).isCloseTo(truth, within(0.15));
        assertThat(MinHasher.decode(MinHasher.encode(a))).containsExactly(a);
        assertThat(a).hasSize(CONFIG.getMinhashNumHashes());
    }

    @Test
    void signaturesFromOtherParametersNeverCluster() {
        int[] a = MINHASH.signature(CAP);
        int[] b = new MinHasher(64, 16, 5, 7).signature(CAP);
        assertThat(MINHASH.cluster(List.of(a, b), 0.1)).hasSize(2);
        assertThatThrownBy(() -> new MinHasher(100, 32, 5, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clustersVariantsAndSeparatesDifferentClauses() {
        List<String> texts = List.of(
                CAP,
                CAP.replace("twelve months", "twenty-four months"),
                CAP.replace("Customer", "Client"),
                "Either Party may terminate this Agreement for convenience on ninety days written notice to the other "
                        + "Party, and upon termination all accrued payment obligations shall survive.",
                "Either Party may terminate this Agreement for convenience on sixty days written notice to the other "
                        + "Party, and upon termination all accrued payment obligations shall survive.");
        List<int[]> sigs = new ArrayList<>();
        texts.forEach(t -> sigs.add(MINHASH.signature(t)));
        var clusters = MINHASH.cluster(sigs, 0.5);
        assertThat(clusters).hasSize(2);
        assertThat(clusters.get(0)).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(clusters.get(1)).containsExactlyInAnyOrder(3, 4);
        assertThat(MinHasher.medoid(clusters.get(0), sigs)).isIn(0, 1, 2);
    }

    // ── templating ────────────────────────────────────────────────────────────

    @Test
    void templatizeRemovesClientSpecifics() {
        String raw = "Northwind Systems, Inc. shall pay Contoso Retail LLC $750,000 by March 1, 2027. "
                + "Northwind will notify legal@contoso.com. Contoso may audit.";
        String t = TEMPLATER.templatize(raw, "Northwind Systems, Inc.", "Contoso Retail LLC", Set.of("Project Falcon"));
        assertThat(t).doesNotContain("Northwind").doesNotContain("Contoso").doesNotContain("750,000")
                .doesNotContain("2027").doesNotContain("@")
                .contains("{{partyA.name}} shall pay {{partyB.name}} {{amount}} by {{date}}")
                .contains("{{email}}");
    }

    @Test
    void templatizeUsesConfiguredPlaceholders() {
        ClauseTemplater custom = new ClauseTemplater("[[CLIENT]]", "[[COUNTERPARTY]]", "[[X]]",
                ConfigFixtures.vocabulary().entityPatterns(), List.of("gmbh"), Set.of());
        assertThat(custom.templatize("Acme Widgets GmbH pays Beta Corp €12,500.", "Acme Widgets GmbH", "Beta Corp", Set.of()))
                .isEqualTo("[[CLIENT]] pays [[COUNTERPARTY]] {{amount}}.");
    }

    // ── article split ─────────────────────────────────────────────────────────

    @Test
    void splitsDraftArticles() {
        String text = "SOFTWARE LICENSE\nARTICLE 1 — DEFINITIONS\n1. \"Software\" means X.\n"
                + "ARTICLE 2 — LIMITATION OF LIABILITY\n1. Cap.\n2. Exclusions.\nSIGNATURES";
        var articles = ArticleSplitter.split(text);
        assertThat(articles).extracting(ArticleSplitter.Article::number).containsExactly(1, 2);
        assertThat(articles.get(1).title()).isEqualTo("LIMITATION OF LIABILITY");
        assertThat(articles.get(1).body()).startsWith("1. Cap.").contains("Exclusions");
        assertThat(TextSimilarity.normTitle("ARTICLE 2 — Limitation of Liability")).isEqualTo("limitation of liability");
    }
}
