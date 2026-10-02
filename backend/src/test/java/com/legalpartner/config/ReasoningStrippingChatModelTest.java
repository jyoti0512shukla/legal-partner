package com.legalpartner.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningStrippingChatModelTest {

    @Test
    void stripsClosedThinkBlock() {
        assertThat(ReasoningStrippingChatModel.strip("<think>plan the JSON</think>\n{\"a\":1}"))
                .isEqualTo("{\"a\":1}");
    }

    @Test
    void stripsOrphanClosingTag() {
        assertThat(ReasoningStrippingChatModel.strip("reasoning here</think>\nFinal answer"))
                .isEqualTo("Final answer");
    }

    @Test
    void unterminatedThoughtYieldsEmpty() {
        assertThat(ReasoningStrippingChatModel.strip("<think>still thinking when tokens ran out")).isEmpty();
    }

    @Test
    void leavesNormalTextAlone() {
        String legal = "1. The Licensee shall pay the fee within thirty (30) days.";
        assertThat(ReasoningStrippingChatModel.strip(legal)).isEqualTo(legal);
    }

    @Test
    void wrapsModelOutput() {
        ChatLanguageModel raw = messages -> Response.from(AiMessage.from("<think>x</think>ANSWER"));
        ChatLanguageModel wrapped = ReasoningStrippingChatModel.wrap(raw);
        assertThat(wrapped.generate("q")).isEqualTo("ANSWER");
        assertThat(ReasoningStrippingChatModel.wrap(wrapped)).isSameAs(wrapped);
        assertThat(ReasoningStrippingChatModel.wrap(null)).isNull();
    }
}
