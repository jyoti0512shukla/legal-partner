package com.legalpartner.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct HTTP client to vLLM with guided_json constrained decoding + kickstart fallback.
 *
 * Strategy (three attempts in order):
 *  1. guided_json — passes JSON Schema to vLLM/Outlines to physically constrain tokens.
 *     Works on vLLM >= 0.4.0 with outlines installed. Zero parsing needed.
 *  2. Kickstart — if guided_json is ignored (older vLLM), retries without it but appends
 *     "\n{" to the user prompt so the model's first token must be a JSON key. Then parses
 *     the response as "{" + content.
 *  3. JSON extraction — scans the response for any {...} block and tries to parse it.
 *
 * If all three fail the method throws so the caller can surface a clear error.
 */
@Component
@Slf4j
public class VllmGuidedClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String modelName;

    // Matches the outermost JSON object in a string (handles prose wrapping)
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}", Pattern.DOTALL);

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

    public JsonNode generateStructured(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> jsonSchema,
            int maxTokens) {

        if (baseUrl.isBlank()) {
            throw new IllegalStateException("legalpartner.chat-api-url not configured");
        }

        // ── Attempt 1: guided_json (vLLM >= 0.4 + outlines) ──────────────────
        try {
            String content = callVllm(systemPrompt, userPrompt, jsonSchema, maxTokens, false);
            JsonNode result = tryParse(content);
            if (result != null) {
                log.debug("guided_json succeeded, response {} chars", content.length());
                return result;
            }
            log.warn("guided_json response was not JSON (len={}, preview={}). Trying kickstart.",
                    content.length(), preview(content));
        } catch (Exception e) {
            log.warn("guided_json HTTP call failed: {}. Trying kickstart.", e.getMessage());
        }

        // ── Attempt 2: kickstart — prime with `{` so first token is JSON ─────
        // Append to system prompt: explicit JSON-only instruction
        String kickstartSystem = systemPrompt +
                "\n\nCRITICAL: Respond ONLY with a valid JSON object. " +
                "Do NOT include any explanation, preamble, or markdown. " +
                "Output ONLY the raw JSON starting with {";
        // Append `\n{` to user prompt — model's first generated token will be a key name
        String kickstartUser = userPrompt + "\n{";

        try {
            String raw = callVllm(kickstartSystem, kickstartUser, null, maxTokens, false);
            // The model continues from "{", so prepend it back
            String withBrace = "{" + raw;
            JsonNode result = tryParse(withBrace);
            if (result != null) {
                log.info("kickstart fallback succeeded");
                return result;
            }
            // Maybe the model ignored the kickstart and wrote the brace itself
            result = tryParse(raw);
            if (result != null) {
                log.info("kickstart fallback succeeded (model included brace)");
                return result;
            }
            // Try extracting any JSON object from the response
            result = extractJson(withBrace);
            if (result != null) {
                log.info("kickstart fallback: extracted JSON from response");
                return result;
            }
            log.warn("kickstart fallback response also not JSON, preview={}", preview(raw));
        } catch (Exception e) {
            log.warn("kickstart fallback failed: {}", e.getMessage());
        }

        // ── Attempt 3: plain call + brute-force JSON extraction ───────────────
        try {
            String raw = callVllm(kickstartSystem, userPrompt, null, maxTokens, false);
            JsonNode result = extractJson(raw);
            if (result != null) {
                log.info("plain extraction fallback succeeded");
                return result;
            }
        } catch (Exception e) {
            log.warn("plain extraction fallback failed: {}", e.getMessage());
        }

        // All three attempts failed — return an empty object so callers degrade gracefully
        log.error("All structured generation attempts failed for prompt preview: {}", preview(userPrompt));
        return objectMapper.createObjectNode();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private String callVllm(String system, String user,
                             Map<String, Object> guidedJson,
                             int maxTokens, boolean logBody) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        ));
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.0);
        if (guidedJson != null) {
            body.put("guided_json", guidedJson);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("no-op");

        String requestJson = objectMapper.writeValueAsString(body);
        if (logBody) log.debug("vLLM request body: {}", requestJson);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/chat/completions",
                HttpMethod.POST,
                new HttpEntity<>(requestJson, headers),
                String.class
        );

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    /** Try to parse content as JSON. Returns null if it fails. */
    private JsonNode tryParse(String content) {
        if (content == null || content.isBlank()) return null;
        String trimmed = content.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;
        try {
            return objectMapper.readTree(trimmed);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** Find the first {...} block in the content and try to parse it. */
    private JsonNode extractJson(String content) {
        if (content == null) return null;
        Matcher m = JSON_OBJECT.matcher(content);
        while (m.find()) {
            try {
                return objectMapper.readTree(m.group());
            } catch (JsonProcessingException ignored) {
                // keep searching for a valid block
            }
        }
        return null;
    }

    private String preview(String s) {
        if (s == null) return "null";
        return s.substring(0, Math.min(150, s.length())).replace('\n', ' ');
    }
}
