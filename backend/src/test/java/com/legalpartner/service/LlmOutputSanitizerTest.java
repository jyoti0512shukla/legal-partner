package com.legalpartner.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** output_cleanup.yml loads and the pipeline removes each class of artifact. */
class LlmOutputSanitizerTest {

    private static final String PROSE = "1. The Licensor shall indemnify the Licensee against all third-party claims arising from infringement.";
    private final LlmOutputSanitizer s = load();

    private static LlmOutputSanitizer load() {
        LlmOutputSanitizer x = new LlmOutputSanitizer();
        ReflectionTestUtils.invokeMethod(x, "load");
        return x;
    }

    @Test
    void stripsChatTemplateTokensAndFences() {
        assertThat(s.clean("<|im_start|>assistant\n```html\n" + PROSE + "\n```<|im_end|>")).isEqualTo(PROSE);
        assertThat(s.clean("[INST] " + PROSE + " [/INST]")).isEqualTo(PROSE);
    }

    @Test
    void truncatesAtEchoedPromptAndTrainingMarkers() {
        assertThat(s.clean(PROSE + "\nREWRITE REQUIRED — fix it")).isEqualTo(PROSE);
        assertThat(s.clean(PROSE + " __SOME_TOKEN__ junk")).isEqualTo(PROSE);
        assertThat(s.clean(PROSE + "\n[Source 3: deal.pdf | LIABILITY]")).isEqualTo(PROSE);
    }

    @Test
    void unwrapsJsonAndLatexAndDropsMetaLines() {
        String json = "{\"suggested_language\": \"" + PROSE.substring(3) + "\", \"rationale\": \"x\"}";
        assertThat(s.clean(json)).isEqualTo(PROSE.substring(3));
        assertThat(s.clean("Here is the clause:\n" + PROSE + "\nNote: This clause is drafted for you")).isEqualTo(PROSE);
        assertThat(s.clean("$\\text{Acme}$ shall **promptly** pay.")).isEqualTo("Acme shall promptly pay.");
    }

    @Test
    void cutsGenerationLoops() {
        String loop = PROSE + " " + PROSE + " " + PROSE + " " + PROSE;
        String out = s.clean(loop);
        assertThat(out).startsWith(PROSE);
        assertThat(out.indexOf("Licensor", PROSE.length())).isEqualTo(-1);
    }

    @Test
    void artifactWarningsRespectContractType() {
        assertThat(s.artifactWarnings("Comply with FAR § 52.1 as applicable.", "SAAS"))
                .singleElement().asString().contains("FAR § 5");
        assertThat(s.artifactWarnings("Comply with FAR § 52.1 as applicable.", "Federal Services")).isEmpty();
        assertThat(s.artifactWarnings("x __RESPONSE__ y", null)).singleElement().asString().contains("'__RESPONSE__'");
        assertThat(s.artifactWarnings(PROSE, "NDA")).isEmpty();
    }
}
