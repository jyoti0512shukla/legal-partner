package com.legalpartner.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct HTTP client to an OpenAI-compatible endpoint (self-hosted vLLM or a hosted
 * provider) with schema-constrained decoding + kickstart fallback.
 *
 * Strategy (three attempts in order):
 *  1. Schema-constrained — sends the JSON Schema via {@link StructuredOutputMode}
 *     (default: OpenAI-standard response_format json_schema). vLLM enforces it with
 *     xgrammar; hosted providers enforce or honour it. Zero parsing needed.
 *  2. Kickstart — if the schema was ignored, retries without it but appends "\n{" to
 *     the user prompt so the model's first token must be a JSON key.
 *  3. JSON extraction — scans the response for any {...} block and tries to parse it.
 *
 * If all three fail the method returns an empty object so the caller can fall back.
 */
@Component
@Slf4j
public class VllmGuidedClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final StructuredOutputMode structuredOutputMode;
    /** Extra top-level fields merged into every request body (e.g. reasoning switches). */
    private final Map<String, Object> requestExtras;
    @Nullable
    private final ChatLanguageModel jsonChatModel;

    /**
     * How the JSON schema is sent. vLLM removed the legacy {@code guided_json} field in
     * v0.12, so the default is the OpenAI-standard {@code response_format}, which both
     * vLLM (xgrammar backend) and hosted providers (OpenRouter, DeepInfra) accept.
     */
    public enum StructuredOutputMode {
        /** {@code response_format: {type: json_schema, json_schema: {...}}} — OpenAI standard. */
        RESPONSE_FORMAT,
        /** {@code structured_outputs: {json: schema}} — vLLM-native (v0.12+). */
        STRUCTURED_OUTPUTS,
        /** Don't send a schema; rely on prompt instructions and the fallback ladder. */
        NONE;

        static StructuredOutputMode parse(String raw) {
            if (raw == null || raw.isBlank()) return RESPONSE_FORMAT;
            return switch (raw.trim().toLowerCase().replace('-', '_')) {
                case "structured_outputs" -> STRUCTURED_OUTPUTS;
                case "none", "off" -> NONE;
                default -> RESPONSE_FORMAT;
            };
        }
    }

    // Matches the outermost JSON object in a string (handles prose wrapping)
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}", Pattern.DOTALL);

    public VllmGuidedClient(
            @Value("${legalpartner.chat-api-url:}") String chatApiUrl,
            @Value("${legalpartner.chat-api-model:mistralai/Mistral-7B-Instruct-v0.2}") String modelName,
            @Value("${legalpartner.chat-api-key:no-op}") String apiKey,
            @Value("${legalpartner.llm.structured-output-mode:response_format}") String structuredOutputMode,
            @Value("${legalpartner.llm.request-extras:}") String requestExtrasJson,
            @Qualifier("jsonChatModel") @Nullable ChatLanguageModel jsonChatModel) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(300_000);
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = chatApiUrl.isBlank() ? ""
                : (chatApiUrl.endsWith("/v1") ? chatApiUrl : chatApiUrl + "/v1");
        this.modelName = modelName;
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? "no-op" : apiKey;
        this.structuredOutputMode = StructuredOutputMode.parse(structuredOutputMode);
        this.requestExtras = parseExtras(requestExtrasJson);
        this.jsonChatModel = jsonChatModel;
        log.info("VllmGuidedClient: model={}, structured-output-mode={}, extras={}",
                modelName, this.structuredOutputMode, this.requestExtras.keySet());
    }

    private Map<String, Object> parseExtras(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed;
        } catch (Exception e) {
            log.warn("Ignoring invalid legalpartner.llm.request-extras JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Mistral-family models (SaulLM, AALAP, Mixtral) use the {@code [INST]} template on the
     * raw completions endpoint. Everything else goes through chat completions.
     */
    boolean usesMistralTemplate() {
        String m = modelName == null ? "" : modelName.toLowerCase();
        return m.contains("mistral") || m.contains("mixtral") || m.contains("saul") || m.contains("aalap");
    }

    public JsonNode generateStructured(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> jsonSchema,
            int maxTokens) {

        if (baseUrl.isBlank()) {
            return fallbackToLangChain(systemPrompt, userPrompt);
        }

        // ── Attempt 1: schema-constrained decoding ────────────────────────────
        try {
            String content = callVllm(systemPrompt, userPrompt, jsonSchema, maxTokens);
            JsonNode result = tryParse(content);
            if (result != null) {
                log.debug("Structured output ({}) succeeded, response {} chars", structuredOutputMode, content.length());
                return result;
            }
            log.warn("Schema-constrained response ({}) was not JSON — trying kickstart.", structuredOutputMode);
        } catch (LlmUnavailableException e) {
            throw e;  // endpoint is down — no point retrying, surface immediately
        } catch (Exception e) {
            log.warn("Schema-constrained call ({}) failed ({}). Trying kickstart.",
                    structuredOutputMode, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
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
     * Best-effort prose generation — used as fallback when structured output is unavailable.
     * Tries chat completions and returns raw text (no JSON parsing).
     * Callers should run this through a prose/proximity parser.
     */
    public String generateProse(String systemPrompt, String userPrompt, int maxTokens) {
        if (baseUrl.isBlank()) return "";
        try {
            return callVllm(systemPrompt, userPrompt, null, maxTokens);
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("generateProse failed: {}", e.getMessage());
            return "";
        }
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
            return fallbackToLangChainText(systemPrompt, userPrompt, responsePrefix);
        }
        String prefix = responsePrefix != null ? responsePrefix : "";
        if (!usesMistralTemplate()) {
            // Non-Mistral models: chat completions, ask the model to start with the prefix.
            try {
                String user = userPrompt.trim()
                        + (prefix.isBlank() ? "" : "\n\nBegin your response with exactly: " + prefix);
                String out = callVllm(systemPrompt, user, null, maxTokens).strip();
                return (prefix.isBlank() || out.startsWith(prefix)) ? out : prefix + out;
            } catch (LlmUnavailableException e) {
                throw e;
            } catch (Exception e) {
                log.warn("generateText (chat) failed: {}", e.getMessage());
                return "";
            }
        }
        // Mistral instruct template: <s>[INST] {instruction} [/INST]
        // Prepend system content inside the [INST] block (Mistral has no separate <<SYS>> tag).
        String prompt = "<s>[INST] " + systemPrompt.trim() + "\n\n" + userPrompt.trim()
                + " [/INST] " + prefix;

        try {
            return prefix + callCompletions(prompt, maxTokens);
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
        requestExtras.forEach(body::putIfAbsent);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

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
        Map<String, Object> body = buildChatRequestBody(system, user, guidedJson, maxTokens);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

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
        return com.legalpartner.config.ReasoningStrippingChatModel.strip(
                root.path("choices").path(0).path("message").path("content").asText());
    }

    /**
     * Chat-completions request body. Package-private for tests.
     *
     * Mistral/SaulLM chat templates do not support a separate "system" role — merging
     * system content into the user message avoids the 400 "roles must alternate" error
     * and is harmless for other models.
     */
    Map<String, Object> buildChatRequestBody(String system, String user,
                                             @Nullable Map<String, Object> jsonSchema, int maxTokens) {
        String fullUser = (system == null || system.isBlank()) ? user : system + "\n\n" + user;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", List.of(Map.of("role", "user", "content", fullUser)));
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.0);
        if (jsonSchema != null) {
            switch (structuredOutputMode) {
                case RESPONSE_FORMAT -> {
                    Map<String, Object> jsonSchemaSpec = new LinkedHashMap<>();
                    jsonSchemaSpec.put("name", "structured_output");
                    jsonSchemaSpec.put("schema", jsonSchema);
                    // strict=false: our schemas don't set additionalProperties=false everywhere,
                    // which OpenAI-strict providers would reject. vLLM enforces the schema either way.
                    jsonSchemaSpec.put("strict", false);
                    body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchemaSpec));
                }
                case STRUCTURED_OUTPUTS -> body.put("structured_outputs", Map.of("json", jsonSchema));
                case NONE -> { }
            }
        }
        requestExtras.forEach(body::putIfAbsent);
        return body;
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

    // ── Fallback: use LangChain4j ChatLanguageModel when vLLM URL is not set (e.g. Gemini) ──

    private JsonNode fallbackToLangChain(String systemPrompt, String userPrompt) {
        if (jsonChatModel == null) {
            log.error("No LLM configured — neither vLLM URL nor Gemini API key set");
            return objectMapper.createObjectNode();
        }
        log.debug("Using LangChain4j fallback for structured generation");
        try {
            String combined = systemPrompt.trim() + "\n\n" + userPrompt.trim()
                    + "\n\nRespond ONLY with valid JSON object (not an array). Wrap arrays in an object.";
            String response = jsonChatModel.generate(combined);
            JsonNode result = tryParse(response);
            if (result != null) {
                // If Gemini returned a JSON array, wrap it in an object
                // e.g., checklist returns [{...}] but we need {"clauses": [{...}]}
                if (result.isArray()) {
                    ObjectNode wrapper = objectMapper.createObjectNode();
                    wrapper.set("clauses", result);
                    return wrapper;
                }
                return result;
            }
            result = extractJson(response);
            if (result != null) return result;
            // Last resort: try to find a JSON array in the response
            String trimmed = response.trim();
            int arrStart = trimmed.indexOf('[');
            int arrEnd = trimmed.lastIndexOf(']');
            if (arrStart >= 0 && arrEnd > arrStart) {
                try {
                    JsonNode arr = objectMapper.readTree(trimmed.substring(arrStart, arrEnd + 1));
                    if (arr.isArray()) {
                        ObjectNode wrapper = objectMapper.createObjectNode();
                        wrapper.set("clauses", arr);
                        return wrapper;
                    }
                } catch (Exception ignored) {}
            }
            log.warn("LangChain4j fallback response was not JSON: {}", preview(response));
        } catch (Exception e) {
            log.error("LangChain4j fallback failed: {}", e.getMessage());
        }
        return objectMapper.createObjectNode();
    }

    private String fallbackToLangChainText(String systemPrompt, String userPrompt, String responsePrefix) {
        if (jsonChatModel == null) {
            log.error("No LLM configured — neither vLLM URL nor Gemini API key set");
            return "";
        }
        log.debug("Using LangChain4j fallback for text generation");
        try {
            String combined = systemPrompt.trim() + "\n\n" + userPrompt.trim();
            if (responsePrefix != null && !responsePrefix.isBlank()) {
                combined += "\n\nStart your response with: " + responsePrefix;
            }
            return jsonChatModel.generate(combined);
        } catch (Exception e) {
            log.error("LangChain4j text fallback failed: {}", e.getMessage());
            return "";
        }
    }
}
