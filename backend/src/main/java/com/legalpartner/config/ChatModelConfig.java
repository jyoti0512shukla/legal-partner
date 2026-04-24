package com.legalpartner.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * LLM provider configuration — switch via LEGALPARTNER_CHAT_PROVIDER env var.
 *
 * .env examples:
 *
 *   # Option A: Own model via vLLM (default)
 *   LEGALPARTNER_CHAT_PROVIDER=vllm
 *   LEGALPARTNER_CHAT_API_URL=https://your-ngrok-url/v1
 *   LEGALPARTNER_CHAT_API_MODEL=jyoti0512shuklaorg/saul-legal-v3
 *
 *   # Option B: Google Gemini
 *   LEGALPARTNER_CHAT_PROVIDER=gemini
 *   LEGALPARTNER_GEMINI_API_KEY=AIzaSy...
 *   LEGALPARTNER_GEMINI_MODEL=gemini-2.5-flash
 */
@Configuration
@Slf4j
public class ChatModelConfig {

    @Value("${legalpartner.chat-provider:vllm}")
    private String provider;

    @Value("${legalpartner.chat-api-url:}")
    private String vllmBaseUrl;

    @Value("${legalpartner.chat-api-model:mistralai/Mistral-7B-Instruct-v0.2}")
    private String vllmModel;

    @Value("${legalpartner.gemini-api-key:}")
    private String geminiApiKey;

    @Value("${legalpartner.gemini-model:gemini-2.5-flash}")
    private String geminiModel;

    // ── vLLM parameters (configurable via application.yml) ───────────────────

    @Value("${legalpartner.llm.timeout-seconds:600}")
    private int llmTimeoutSeconds;

    @Value("${legalpartner.llm.chat.max-tokens:4000}")
    private int chatMaxTokens;

    @Value("${legalpartner.llm.chat.frequency-penalty:0.1}")
    private double chatFrequencyPenalty;

    @Value("${legalpartner.llm.short.max-tokens:2000}")
    private int shortMaxTokens;

    @Value("${legalpartner.llm.short.temperature:0.3}")
    private double shortTemperature;

    @Value("${legalpartner.llm.short.frequency-penalty:0.1}")
    private double shortFrequencyPenalty;

    @Value("${legalpartner.llm.json.max-tokens:2000}")
    private int jsonMaxTokens;

    @Value("${legalpartner.llm.json.temperature:0.1}")
    private double jsonTemperature;

    @Value("${legalpartner.llm.json.frequency-penalty:0.3}")
    private double jsonFrequencyPenalty;

    /** Comma-separated stop sequences for the draft (chat) model. */
    @Value("${legalpartner.llm.stop-sequences:[/INST],[INST],__PROCESSED_REQUEST__,__INSTRUCTION__,__RESPONSE__,__FOLLOW_UP_QUESTIONS__,__FORBIDDEN_ACTIONS,__CONFIRMATION_OF_UNDERSTANDING__,__BEGIN_PREMIUM_INSTRUCTIONS__,\\nCFR § ,\\nFAR § }")
    private String stopSequencesRaw;

    @Bean
    @Primary
    ChatLanguageModel chatLanguageModel() {
        if ("gemini".equalsIgnoreCase(provider)) {
            log.info("LLM provider: Google Gemini ({})", geminiModel);
            return GoogleAiGeminiChatModel.builder()
                    .apiKey(geminiApiKey)
                    .modelName(geminiModel)
                    .timeout(Duration.ofSeconds(120))
                    .maxOutputTokens(6000)
                    .temperature(0.3)
                    .build();
        }

        if (vllmBaseUrl != null && !vllmBaseUrl.isBlank()) {
            String url = vllmBaseUrl.endsWith("/v1") ? vllmBaseUrl : vllmBaseUrl + "/v1";
            log.info("LLM provider: vLLM ({} @ {})", vllmModel, url);
            return OpenAiChatModel.builder()
                    .baseUrl(url)
                    .apiKey("no-op")
                    .modelName(vllmModel)
                    .timeout(Duration.ofSeconds(llmTimeoutSeconds))
                    .maxTokens(chatMaxTokens)
                    .frequencyPenalty(chatFrequencyPenalty)
                    // Stop the moment v3 emits any of its training-format markers.
                    // These appeared inside generated drafts (e.g. "__PROCESSED_REQUEST__"
                    // followed by pages of federal-contracting boilerplate).
                    .stop(parseStopSequences())
                    .build();
        }

        log.warn("No LLM provider configured — chat features will fail");
        return null;
    }

    /** Parse comma-separated stop sequences from config, restoring escaped newlines. */
    private List<String> parseStopSequences() {
        if (stopSequencesRaw == null || stopSequencesRaw.isBlank()) return List.of();
        return Arrays.stream(stopSequencesRaw.split(","))
                .map(s -> s.replace("\\n", "\n"))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Smaller-budget model for everything that's NOT drafting — risk assessment,
     * extraction, summary, ask-AI, query, compare. These produce short outputs
     * (pipe-delimited lines, one-paragraph answers, a page of markdown) so the
     * 6000-token budget on the draft-primary model just lets v3 ramble and
     * adds seconds of latency for no quality gain.
     *
     * Keep frequencyPenalty low (0.1) for the same "legal text repeats terms"
     * reason as the draft model. No stop sequences — those were drafting-specific.
     */
    @Bean("shortChatModel")
    ChatLanguageModel shortChatModel() {
        if ("gemini".equalsIgnoreCase(provider)) {
            return GoogleAiGeminiChatModel.builder()
                    .apiKey(geminiApiKey)
                    .modelName(geminiModel)
                    .timeout(Duration.ofSeconds(120))
                    .maxOutputTokens(2000)
                    .temperature(0.3)
                    .build();
        }
        if (vllmBaseUrl != null && !vllmBaseUrl.isBlank()) {
            String url = vllmBaseUrl.endsWith("/v1") ? vllmBaseUrl : vllmBaseUrl + "/v1";
            return OpenAiChatModel.builder()
                    .baseUrl(url)
                    .apiKey("no-op")
                    .modelName(vllmModel)
                    .timeout(Duration.ofSeconds(llmTimeoutSeconds))
                    .maxTokens(shortMaxTokens)
                    .temperature(shortTemperature)
                    .frequencyPenalty(shortFrequencyPenalty)
                    .build();
        }
        return null;
    }

    @Bean("jsonChatModel")
    ChatLanguageModel jsonChatModel() {
        if ("gemini".equalsIgnoreCase(provider)) {
            return GoogleAiGeminiChatModel.builder()
                    .apiKey(geminiApiKey)
                    .modelName(geminiModel)
                    .timeout(Duration.ofSeconds(120))
                    .responseFormat(ResponseFormat.JSON)
                    .maxOutputTokens(2000)
                    .temperature(0.1)
                    .build();
        }

        if (vllmBaseUrl != null && !vllmBaseUrl.isBlank()) {
            String url = vllmBaseUrl.endsWith("/v1") ? vllmBaseUrl : vllmBaseUrl + "/v1";
            return OpenAiChatModel.builder()
                    .baseUrl(url)
                    .apiKey("no-op")
                    .modelName(vllmModel)
                    .timeout(Duration.ofSeconds(llmTimeoutSeconds))
                    .responseFormat("json_object")
                    .maxTokens(jsonMaxTokens)
                    .temperature(jsonTemperature)
                    .frequencyPenalty(jsonFrequencyPenalty)
                    .build();
        }

        log.warn("No LLM provider configured — JSON chat features will fail");
        return null;
    }
}
