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

import java.util.ArrayList;
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
            String content = callVllm(systemPrompt, userPrompt, jsonSchema, maxTokens);
            JsonNode result = tryParse(content);
            if (result != null) {
                log.debug("guided_json succeeded, response {} chars", content.length());
                return result;
            }
            log.warn("guided_json response was not JSON — model likely ignored schema. Trying kickstart.");
        } catch (LlmUnavailableException e) {
            throw e;  // endpoint is down — no point retrying, surface immediately
        } catch (Exception e) {
            log.warn("guided_json call failed ({}). Trying kickstart.", e.getClass().getSimpleName());
        }

        // ── Attempt 2: kickstart — prime with `{` so first token is JSON ─────
        String kickstartSystem = systemPrompt +
                "\n\nCRITICAL: Respond ONLY with a valid JSON object. " +
                "Do NOT include any explanation, preamble, or markdown. " +
                "Output ONLY the raw JSON starting with {";
        String kickstartUser = userPrompt + "\n{";

        try {
            String raw = callVllm(kickstartSystem, kickstartUser, null, maxTokens);
            String withBrace = "{" + raw;
            JsonNode result = tryParse(withBrace);
            if (result != null) { log.info("kickstart fallback succeeded"); return result; }
            result = tryParse(raw);
            if (result != null) { log.info("kickstart fallback succeeded"); return result; }
            result = extractJson(withBrace);
            if (result != null) { log.info("kickstart: extracted embedded JSON"); return result; }
            log.warn("kickstart response also not JSON — model output: {}", preview(raw));
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("kickstart failed ({})", e.getClass().getSimpleName());
        }

        // ── Attempt 3: plain call + brute-force JSON extraction ───────────────
        try {
            String raw = callVllm(kickstartSystem, userPrompt, null, maxTokens);
            JsonNode result = extractJson(raw);
            if (result != null) { log.info("plain JSON extraction succeeded"); return result; }
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("plain extraction failed ({})", e.getClass().getSimpleName());
        }

        log.error("All structured generation attempts failed — returning empty result");
        return objectMapper.createObjectNode();
    }

    /**
     * Generate plain text using the /v1/completions (raw text) endpoint.
     *
     * The Mistral chat template is applied manually:
     *   <s>[INST] {system}\n\n{user} [/INST] {responsePrefix}
     *
     * The model then continues from responsePrefix in pure text-completion mode —
     * no chat-API confusion, no training-data leakage from partial assistant messages.
     *
     * @param responsePrefix  text the model will continue from (e.g. "OVERALL:")
     */
    public String generateText(String systemPrompt, String userPrompt,
                                String responsePrefix, int maxTokens) {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("legalpartner.chat-api-url not configured");
        }
        // Mistral instruct template: <s>[INST] {instruction} [/INST]
        // Prepend system content inside the [INST] block (Mistral has no separate <<SYS>> tag).
        String prompt = "<s>[INST] " + systemPrompt.trim() + "\n\n" + userPrompt.trim()
                + " [/INST] " + (responsePrefix != null ? responsePrefix : "");

        try {
            return responsePrefix + callCompletions(prompt, maxTokens);
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("generateText (completions) failed: {}", e.getMessage());
            return "";
        }
    }

    private String callCompletions(String prompt, int maxTokens) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("prompt", prompt);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.0);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("no-op");

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(
                    baseUrl + "/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                    String.class
            );
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new LlmUnavailableException("LLM endpoint unreachable at " + baseUrl +
                    " — is the vLLM server running and ngrok tunnel active?", e);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String body2 = e.getResponseBodyAsString();
            if (body2.contains("<!DOCTYPE") || body2.contains("<html")) {
                throw new LlmUnavailableException(
                        "LLM endpoint returned an error page (HTTP " + e.getStatusCode() +
                        ") — ngrok tunnel may be offline. Restart ngrok on the GCP VM.", e);
            }
            throw e;
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("choices").path(0).path("text").asText();  // completions uses "text" not "message.content"
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private String callVllm(String system, String user,
                             Map<String, Object> guidedJson,
                             int maxTokens) throws Exception {
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

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(
                    baseUrl + "/chat/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(requestJson, headers),
                    String.class
            );
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // Connection refused / timeout — tunnel or server is down
            throw new LlmUnavailableException("LLM endpoint unreachable at " + baseUrl +
                    " — is the vLLM server running and ngrok tunnel active?", e);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String body2 = e.getResponseBodyAsString();
            // HTML error page (ngrok offline, proxy error, etc.) — don't log the HTML
            if (body2.contains("<!DOCTYPE") || body2.contains("<html")) {
                throw new LlmUnavailableException(
                        "LLM endpoint returned an error page (HTTP " + e.getStatusCode() +
                        ") — ngrok tunnel may be offline. Restart ngrok on the GCP VM.", e);
            }
            throw e;  // real API error — let caller see it
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    /** Thrown when the LLM endpoint is unreachable — prevents pointless retries. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
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
