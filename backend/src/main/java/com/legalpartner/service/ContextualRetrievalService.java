package com.legalpartner.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Anthropic-style Contextual Retrieval — before embedding each chunk, prepend
 * 50-100 tokens of document-level context. Improves retrieval quality 49-67%
 * vs plain dense embedding in published benchmarks.
 *
 * Reference: https://www.anthropic.com/news/contextual-retrieval
 *
 * This runs at ingest time, once per chunk. Uses the small chat model so each
 * call is fast (~1s). If the LLM call fails, we fall back to the original chunk
 * text — ingestion shouldn't fail because context generation failed.
 */
@Service
@Slf4j
public class ContextualRetrievalService {

    private final ChatLanguageModel contextModel;
    private final com.legalpartner.config.PromptRepository prompts;

    @Value("${legalpartner.text.contextual-summary-cap-chars:4000}")
    private int contextualSummaryCapChars;

    public ContextualRetrievalService(
            @Qualifier("shortChatModel") ChatLanguageModel shortChatModel,
            com.legalpartner.config.PromptRepository prompts) {
        this.contextModel = shortChatModel;
        this.prompts = prompts;
    }

    /**
     * Generate a short situating context for a chunk inside a document. The
     * returned string is prepended (with a separator) to the chunk before
     * embedding.
     *
     * @param documentSummary a brief description of the whole document
     *                        (filename + first 500 chars of its text is fine)
     * @param chunkText       the raw chunk text
     * @return "Context: ...\n\n<chunk>" on success, plain chunk on failure
     */
    public String contextualise(String documentSummary, String chunkText) {
        if (chunkText == null || chunkText.isBlank()) return chunkText;

        String prompt = prompts.get("CONTEXTUAL_RETRIEVAL_CHUNK").formatted(summaryOf(documentSummary), chunkText);

        try {
            AiMessage response = contextModel.generate(UserMessage.from(prompt)).content();
            String context = response.text().trim();
            if (context.isBlank()) return chunkText;
            return "Context: " + context + "\n\n" + chunkText;
        } catch (Exception e) {
            log.debug("Contextual retrieval: context generation failed ({}), using raw chunk", e.getMessage());
            return chunkText;
        }
    }

    /** Cap the document summary to avoid blowing the prompt budget on long docs. */
    private String summaryOf(String doc) {
        if (doc == null) return "";
        return doc.length() > contextualSummaryCapChars ? doc.substring(0, contextualSummaryCapChars) + "\n[... truncated]" : doc;
    }
}
