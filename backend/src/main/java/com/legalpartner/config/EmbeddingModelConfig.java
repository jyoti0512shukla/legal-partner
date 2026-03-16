package com.legalpartner.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * When LEGALPARTNER_EMBEDDING_API_URL is set (e.g. Colab embedding server ngrok URL),
 * use OpenAI-compatible embedding API instead of Ollama.
 * Used for low-resource dev when laptop cannot run Ollama.
 */
@Configuration
public class EmbeddingModelConfig {

    @Bean
    @Primary
    @ConditionalOnExpression("'${legalpartner.embedding-api-url:}'.length() > 0")
    EmbeddingModel openAiEmbeddingModel(
            @Value("${legalpartner.embedding-api-url}") String baseUrl,
            @Value("${legalpartner.embedding-api-model:all-MiniLM-L6-v2}") String modelName) {
        String url = baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1";
        return OpenAiEmbeddingModel.builder()
                .baseUrl(url)
                .apiKey("no-op")
                .modelName(modelName)
                .build();
    }
}
