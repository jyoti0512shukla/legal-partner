package com.legalpartner.service.review;

import com.legalpartner.testsupport.ConfigFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClauseInventoryTest {

    private static final Map<String, List<String>> KEYWORDS = ConfigFixtures.riskQuestions().getClauseKeywords();

    private static final String CONTRACT = """
            MASTER SERVICES AGREEMENT
            This Agreement sets the standard terms. Any amendment must be in writing.

            ARTICLE 1 — CONFIDENTIALITY
            Each party shall keep the other party's Confidential Information confidential and shall not disclose it
            to any third party except its employees who need to know it for the purposes of this Agreement.

            ARTICLE 2 — LIMITATION OF LIABILITY
            The aggregate liability of each party shall not exceed the fees paid in the twelve months preceding the claim.
            Neither party shall be liable for any indirect, special or consequential damages whatsoever.

            ARTICLE 3 — GOVERNING LAW
            This Agreement is governed by the laws of the State of Delaware and the parties submit to its courts
            for the resolution of any dispute arising out of or relating to this Agreement.
            """;

    @Test
    void headingFirstMatchPicksTheRightSection() {
        var inv = ClauseInventory.inventory(CONTRACT, KEYWORDS, 4000);
        assertThat(inv.get("LIABILITY")).startsWith("ARTICLE 2");
        assertThat(inv.get("CONFIDENTIALITY")).startsWith("ARTICLE 1");
        assertThat(inv.get("GOVERNING_LAW")).startsWith("ARTICLE 3");
    }

    @Test
    void shortKeywordsMatchWholeWordsOnly() {
        // "nda" must not match "standard"/"amendment"; "sla" must not match "legislation".
        assertThat(ClauseInventory.indexOfKeyword("the standard amendment", "nda", 0)).isEqualTo(-1);
        assertThat(ClauseInventory.indexOfKeyword("subject to legislation", "sla", 0)).isEqualTo(-1);
        assertThat(ClauseInventory.indexOfKeyword("this nda binds", "nda", 0)).isEqualTo(5);
        assertThat(ClauseInventory.indexOfKeyword("the sla is 99.9%", "sla", 0)).isEqualTo(4);
    }

    @Test
    void noSlaClauseInventedFromUnrelatedWords() {
        String text = "ARTICLE 1 — COMPLIANCE\nEach party shall comply with applicable legislation and "
                + "regulations in force from time to time, including export controls and sanctions law.";
        assertThat(ClauseInventory.inventory(text, KEYWORDS, 4000)).doesNotContainKey("SLA");
    }
}
