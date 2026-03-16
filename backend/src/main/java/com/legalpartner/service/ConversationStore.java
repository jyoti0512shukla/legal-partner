package com.legalpartner.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversation session store with:
 * - Per-turn question embeddings (for semantic relevance pruning)
 * - Token estimation (word count × 1.4, calibrated for legal text)
 * - Rolling summarization support (summary + recent verbatim turns)
 * - Hard cap on stored turns for memory safety
 */
@Component
public class ConversationStore {

    @Value("${legalpartner.conversation.max-history-turns:10}")
    private int maxHistoryTurns;

    public record ConversationTurn(
            String question,
            String answer,
            float[] questionEmbedding,   // null if embedding not available
            int estimatedTokens          // precomputed: estimateTokens(q) + estimateTokens(a)
    ) {}

    public record ConversationSession(
            Deque<ConversationTurn> turns,   // verbatim recent turns (post-compression: only last N)
            String summary,                   // null until first compression; rolling merged summary
            int summaryTokens                 // estimated token cost of summary string
    ) {
        public List<ConversationTurn> allTurns() {
            return new ArrayList<>(turns);
        }

        public int totalTokens() {
            int turnTokens = turns.stream().mapToInt(ConversationTurn::estimatedTokens).sum();
            return turnTokens + summaryTokens;
        }
    }

    private final Map<String, ConversationSession> store = new ConcurrentHashMap<>();

    /**
     * Add a completed Q&A turn to the session.
     * @param questionEmbedding float[] from EmbeddingModel.embed(question).content().vector(); may be null
     */
    public void add(String conversationId, String question, String answer, float[] questionEmbedding) {
        if (conversationId == null || conversationId.isBlank()) return;
        int tokens = estimateTokens(question) + estimateTokens(answer);
        ConversationTurn turn = new ConversationTurn(question, answer, questionEmbedding, tokens);

        store.compute(conversationId, (id, session) -> {
            if (session == null) {
                Deque<ConversationTurn> deque = new ArrayDeque<>();
                deque.addLast(turn);
                return new ConversationSession(deque, null, 0);
            }
            session.turns().addLast(turn);
            // Hard cap prevents unbounded memory growth
            while (session.turns().size() > maxHistoryTurns) {
                session.turns().removeFirst();
            }
            return session;
        });
    }

    /** Retrieve the full session object, or null if no session exists. */
    public ConversationSession getSession(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        return store.get(conversationId);
    }

    /** Total estimated token cost of all history (summary + verbatim turns). */
    public int getTotalHistoryTokens(String conversationId) {
        ConversationSession session = store.get(conversationId);
        return session == null ? 0 : session.totalTokens();
    }

    /**
     * Replace all-but-last-N verbatim turns with a compressed summary.
     * Called by AiService after summarization is complete.
     */
    public void compressHistory(String conversationId, String newSummary, int keepVerbatimTurns) {
        store.compute(conversationId, (id, session) -> {
            if (session == null) return null;
            List<ConversationTurn> all = new ArrayList<>(session.turns());
            Deque<ConversationTurn> kept = new ArrayDeque<>();
            int startIdx = Math.max(0, all.size() - keepVerbatimTurns);
            for (int i = startIdx; i < all.size(); i++) {
                kept.addLast(all.get(i));
            }
            return new ConversationSession(kept, newSummary, estimateTokens(newSummary));
        });
    }

    /** Estimate token count. Legal text averages ~1.4 tokens per word (technical terms split). */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int)(text.trim().split("\\s+").length * 1.4);
    }
}
