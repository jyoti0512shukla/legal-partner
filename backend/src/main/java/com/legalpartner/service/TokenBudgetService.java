package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token budget management using vLLM's /tokenize endpoint for exact counts.
 * Caches system prompt token counts (computed once at startup).
 * Document token counts are stored on DocumentMetadata (computed once at index time).
 *
 * Eliminates all character-to-token estimation — uses the actual model tokenizer.
 */
@Service
@Slf4j
public class TokenBudgetService {

    @Value("${legalpartner.llm.chat.api-url:${LEGALPARTNER_CHAT_API_URL:}}")
    private String vllmBaseUrl;

    @Value("${legalpartner.llm.chat.model:${LEGALPARTNER_CHAT_API_MODEL:saullm-54b}}")
    private String modelName;

    @Value("${legalpartner.context.model-window-tokens:8192}")
    private int modelWindowTokens;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Cached token counts for static prompts (system prompts, templates) */
    private final Map<String, Integer> promptTokenCache = new ConcurrentHashMap<>();

    /** Fallback ratio when vLLM is unavailable — conservative 3 chars/token */
    private static final double FALLBACK_CHARS_PER_TOKEN = 3.0;

    @PostConstruct
    void init() {
        log.info("TokenBudgetService initialized: model={}, window={}, vllm={}",
                modelName, modelWindowTokens, vllmBaseUrl != null ? "configured" : "none");
    }

    /**
     * Count tokens using vLLM's /tokenize endpoint (exact, uses model's own tokenizer).
     * Falls back to character estimation if vLLM is unavailable.
     */
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;

        // Try vLLM tokenizer
        if (vllmBaseUrl != null && !vllmBaseUrl.isBlank()) {
            try {
                String url = vllmBaseUrl.endsWith("/v1")
                        ? vllmBaseUrl.replace("/v1", "/tokenize")
                        : vllmBaseUrl + "/tokenize";

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                String body = objectMapper.writeValueAsString(Map.of(
                        "model", modelName,
                        "prompt", text
                ));
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

                JsonNode node = objectMapper.readTree(response.getBody());
                int count = node.path("count").asInt(
                        node.path("tokens").size() > 0 ? node.path("tokens").size() : -1);
                if (count > 0) return count;
            } catch (Exception e) {
                log.debug("vLLM /tokenize failed, using estimation: {}", e.getMessage());
            }
        }

        // Fallback: conservative character estimation
        return (int) Math.ceil(text.length() / FALLBACK_CHARS_PER_TOKEN);
    }

    /**
     * Count tokens for a prompt with caching. Use for static prompts (system prompts).
     * Cache key is the prompt hashCode — invalidated on restart.
     */
    public int countTokensCached(String cacheKey, String text) {
        return promptTokenCache.computeIfAbsent(cacheKey, k -> countTokens(text));
    }

    /**
     * Calculate how many tokens of document text can fit, given a specific output budget
     * and a pre-counted system prompt.
     */
    public int availableContentTokens(int systemPromptTokens, int outputTokens, int questionTokenEstimate) {
        return modelWindowTokens - systemPromptTokens - outputTokens - questionTokenEstimate - 50; // 50 safety margin
    }

    /**
     * Truncate text to fit within a token budget. Uses binary search with tokenizer.
     * Returns the longest prefix that fits within maxTokens.
     */
    public String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return text;

        int currentTokens = countTokens(text);
        if (currentTokens <= maxTokens) return text;

        // Binary search for the right truncation point
        int lo = 0, hi = text.length();
        String best = text.substring(0, Math.min(1000, text.length())); // minimum

        while (lo < hi) {
            int mid = (lo + hi) / 2;
            String candidate = text.substring(0, mid);
            int tokens = countTokens(candidate);
            if (tokens <= maxTokens) {
                best = candidate;
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        log.debug("Truncated {} chars ({} tokens) → {} chars ({} tokens) to fit {} token budget",
                text.length(), currentTokens, best.length(), countTokens(best), maxTokens);
        return best;
    }

    /**
     * Smart truncation — if a relevance hint is provided, center the window on the
     * relevant section before truncating. More useful context than just taking the prefix.
     */
    public String fitToTokenBudget(String text, int maxTokens, String relevanceHint) {
        if (text == null || text.isEmpty()) return text;

        int currentTokens = countTokens(text);
        if (currentTokens <= maxTokens) return text;

        // Estimate char budget from token budget (rough, for windowing)
        int charBudget = (int) (maxTokens * FALLBACK_CHARS_PER_TOKEN);

        String windowed = text;
        if (relevanceHint != null && !relevanceHint.isBlank() && text.length() > charBudget) {
            // Center on relevant section
            String lower = text.toLowerCase();
            int bestIdx = -1;
            for (String word : relevanceHint.toLowerCase().split("\\s+")) {
                if (word.length() < 4) continue;
                int idx = lower.indexOf(word);
                if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) bestIdx = idx;
            }
            if (bestIdx >= 0) {
                int start = Math.max(0, bestIdx - charBudget / 3);
                int end = Math.min(text.length(), start + charBudget);
                windowed = text.substring(start, end);
            } else {
                // No match — take beginning + end
                int half = charBudget / 2;
                windowed = text.substring(0, half) + "\n[...]\n" + text.substring(text.length() - half);
            }
        }

        // Now use exact tokenizer to trim to exact budget
        return truncateToTokenBudget(windowed, maxTokens);
    }

    /** Get the model's context window size */
    public int getModelWindowTokens() {
        return modelWindowTokens;
    }
}
