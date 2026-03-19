package com.legalpartner.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct HTTP client to vLLM's OpenAI-compatible endpoint with guided_json support.
 *
 * guided_json passes a JSON Schema to vLLM which uses Outlines constrained decoding
 * to physically mask illegal tokens at each generation step. The model is UNABLE to
 * produce output that violates the schema — pipes in wrong places, missing keys, wrong
 * enum values, etc. are all impossible. This is categorically more reliable than prompt
 * instructions or response_format=json_object.
 *
 * Requirements: vLLM >= 0.4.0 with `pip install outlines` (included by default in
 * recent vLLM releases). Enable with --enable-auto-tool-choice or just pass guided_json.
 */
@Component
@Slf4j
public class VllmGuidedClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String modelName;

    public VllmGuidedClient(
            @Value("${legalpartner.chat-api-url:}") String chatApiUrl,
            @Value("${legalpartner.chat-api-model:mistralai/Mistral-7B-Instruct-v0.2}") String modelName) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(300_000);
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = chatApiUrl.isBlank() ? ""
                : (chatApiUrl.endsWith("/v1") ? chatApiUrl : chatApiUrl + "/v1");
        this.modelName = modelName;
    }

    /**
     * Generate a structured response guaranteed to match the provided JSON Schema.
     *
     * @param systemPrompt  Task instructions (no format instructions needed — schema handles that)
     * @param userPrompt    The document context + task-specific question
     * @param jsonSchema    JSON Schema map — vLLM will constrain generation to this shape
     * @param maxTokens     Token budget for the response
     * @return Parsed JsonNode guaranteed to conform to the schema
     */
    public JsonNode generateStructured(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> jsonSchema,
            int maxTokens) {

        if (baseUrl.isBlank()) {
            throw new IllegalStateException("legalpartner.chat-api-url not configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.0);   // fully deterministic for structured extraction
        body.put("guided_json", jsonSchema);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("no-op");

        try {
            String requestJson = objectMapper.writeValueAsString(body);
            log.debug("guided_json request to {}: {} chars", baseUrl, requestJson.length());

            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/chat/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(requestJson, headers),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText();

            log.debug("guided_json response length={}", content.length());
            return objectMapper.readTree(content);

        } catch (Exception e) {
            log.error("vLLM guided_json call failed: {}", e.getMessage());
            throw new RuntimeException("Structured generation failed: " + e.getMessage(), e);
        }
    }
}
