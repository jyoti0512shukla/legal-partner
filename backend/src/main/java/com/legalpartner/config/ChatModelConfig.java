package com.legalpartner.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * When LEGALPARTNER_CHAT_API_URL is set (e.g. Colab ngrok URL), use OpenAI-compatible
 * API for chat instead of Ollama. Embeddings remain from Ollama.
 */
@Configuration
public class ChatModelConfig {

    @Bean
    @Primary
    @ConditionalOnExpression("'${legalpartner.chat-api-url:}'.length() > 0")
    ChatLanguageModel openAiChatModel(
            @Value("${legalpartner.chat-api-url}") String baseUrl,
            @Value("${legalpartner.chat-api-model:mistralai/Mistral-7B-Instruct-v0.2}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1")
                .apiKey("no-op")
                .modelName(modelName)
                .build();
    }
}
