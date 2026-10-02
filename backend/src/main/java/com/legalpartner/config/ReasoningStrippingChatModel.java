package com.legalpartner.config;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decorator that removes reasoning traces ({@code <think>…</think>}) from model output.
 *
 * Reasoning models (Qwen 3.x, DeepSeek R1-style, some Gemma/GLM builds) can emit their
 * chain of thought inline before the answer. Every downstream parser in this codebase
 * (JSON extraction, clause QA, CSV fallbacks) assumes the answer starts immediately, so
 * we strip the trace once here instead of in every caller.
 */
public class ReasoningStrippingChatModel implements ChatLanguageModel {

    /** Closed think blocks anywhere in the text. */
    private static final Pattern THINK_BLOCK =
            Pattern.compile("(?is)<think(?:ing)?>.*?</think(?:ing)?>");
    /** Unclosed think block at the start (model hit max tokens mid-thought or omitted the open tag). */
    private static final Pattern LEADING_UNCLOSED =
            Pattern.compile("(?is)^\\s*<think(?:ing)?>.*$");
    /** Some templates emit only the closing tag, with the reasoning before it. */
    private static final Pattern ORPHAN_CLOSE =
            Pattern.compile("(?is)^.*?</think(?:ing)?>");

    private final ChatLanguageModel delegate;

    public ReasoningStrippingChatModel(ChatLanguageModel delegate) {
        this.delegate = delegate;
    }

    public static ChatLanguageModel wrap(ChatLanguageModel model) {
        if (model == null || model instanceof ReasoningStrippingChatModel) return model;
        return new ReasoningStrippingChatModel(model);
    }

    /** Strip reasoning traces from raw model text. Safe on text without traces. */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = THINK_BLOCK.matcher(text).replaceAll("");
        if (ORPHAN_CLOSE.matcher(out).find() && !out.contains("<think")) {
            out = ORPHAN_CLOSE.matcher(out).replaceFirst("");
        }
        if (LEADING_UNCLOSED.matcher(out).matches()) {
            // Entire remaining output is an unterminated thought — nothing usable.
            return "";
        }
        return out.strip();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return clean(delegate.generate(messages));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return clean(delegate.generate(messages, toolSpecifications));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return clean(delegate.generate(messages, toolSpecification));
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    private static Response<AiMessage> clean(Response<AiMessage> response) {
        if (response == null || response.content() == null) return response;
        AiMessage msg = response.content();
        if (msg.text() == null || msg.hasToolExecutionRequests()) return response;
        String stripped = strip(msg.text());
        if (stripped.equals(msg.text())) return response;
        return new Response<>(AiMessage.from(stripped), response.tokenUsage(),
                response.finishReason(), response.metadata());
    }
}
