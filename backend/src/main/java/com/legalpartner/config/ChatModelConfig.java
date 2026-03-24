package com.legalpartner.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

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

    // ── vLLM / OpenAI-compatible (default) ─────────────────────────────────

    @Bean
    @Primary
    @ConditionalOnExpression("'${legalpartner.chat-provider:vllm}' == 'vllm' && '${legalpartner.chat-api-url:}'.length() > 0")
    ChatLanguageModel openAiChatModel(
            @Value("${legalpartner.chat-api-url}") String baseUrl,
            @Value("${legalpartner.chat-api-model:mistralai/Mistral-7B-Instruct-v0.2}") String modelName) {
        log.info("LLM provider: vLLM ({} @ {})", modelName, baseUrl);
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1")
                .apiKey("no-op")
                .modelName(modelName)
                .timeout(Duration.ofSeconds(300))
                .maxTokens(900)
                .frequencyPenalty(0.7)
                .build();
    }

    @Bean("jsonChatModel")
    @ConditionalOnExpression("'${legalpartner.chat-provider:vllm}' == 'vllm' && '${legalpartner.chat-api-url:}'.length() > 0")
    ChatLanguageModel jsonChatModelVllm(
            @Value("${legalpartner.chat-api-url}") String baseUrl,
            @Value("${legalpartner.chat-api-model:mistralai/Mistral-7B-Instruct-v0.2}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1")
                .apiKey("no-op")
                .modelName(modelName)
                .timeout(Duration.ofSeconds(300))
                .responseFormat("json_object")
                .maxTokens(2000)
                .frequencyPenalty(0.3)
                .build();
    }

    // ── Google Gemini ──────────────────────────────────────────────────────

    @Bean
    @Primary
    @ConditionalOnExpression("'${legalpartner.chat-provider:vllm}' == 'gemini'")
    ChatLanguageModel geminiChatModel(
            @Value("${legalpartner.gemini-api-key:}") String apiKey,
            @Value("${legalpartner.gemini-model:gemini-2.5-flash}") String modelName) {
        log.info("LLM provider: Google Gemini ({})", modelName);
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .maxOutputTokens(900)
                .temperature(0.3)
                .build();
    }

    @Bean("jsonChatModel")
    @ConditionalOnExpression("'${legalpartner.chat-provider:vllm}' == 'gemini'")
    ChatLanguageModel jsonChatModelGemini(
            @Value("${legalpartner.gemini-api-key:}") String apiKey,
            @Value("${legalpartner.gemini-model:gemini-2.5-flash}") String modelName) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .responseFormat(ResponseFormat.JSON)
                .maxOutputTokens(2000)
                .temperature(0.1)
                .build();
    }
}
