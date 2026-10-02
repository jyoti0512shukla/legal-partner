package com.legalpartner.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VllmGuidedClientTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object", "properties", Map.of("a", Map.of("type", "string")), "required", List.of("a"));

    private static VllmGuidedClient client(String model, String mode, String extras) {
        return new VllmGuidedClient("http://localhost:8000", model, "sk-test", mode, extras, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultSendsOpenAiResponseFormatNotGuidedJson() {
        var body = client("qwen/qwen3.8-27b", "", "").buildChatRequestBody("sys", "user", SCHEMA, 100);
        assertThat(body).doesNotContainKey("guided_json");
        Map<String, Object> rf = (Map<String, Object>) body.get("response_format");
        assertThat(rf).containsEntry("type", "json_schema");
        Map<String, Object> spec = (Map<String, Object>) rf.get("json_schema");
        assertThat(spec).containsEntry("schema", SCHEMA).containsKey("name");
    }

    @Test
    void structuredOutputsModeUsesVllmNativeField() {
        var body = client("m", "structured_outputs", "").buildChatRequestBody("s", "u", SCHEMA, 100);
        assertThat(body).containsEntry("structured_outputs", Map.of("json", SCHEMA)).doesNotContainKey("response_format");
    }

    @Test
    void noneModeSendsNoSchema() {
        var body = client("m", "none", "").buildChatRequestBody("s", "u", SCHEMA, 100);
        assertThat(body).doesNotContainKeys("response_format", "structured_outputs", "guided_json");
    }

    @Test
    void mergesRequestExtrasWithoutOverridingCoreFields() {
        var body = client("m", "", "{\"reasoning\":{\"enabled\":false},\"model\":\"hijack\"}")
                .buildChatRequestBody("s", "u", null, 100);
        assertThat(body).containsEntry("reasoning", Map.of("enabled", false)).containsEntry("model", "m");
    }

    @Test
    void invalidExtrasAreIgnored() {
        var body = client("m", "", "{not json").buildChatRequestBody("s", "u", null, 100);
        assertThat(body).containsOnlyKeys("model", "messages", "max_tokens", "temperature");
    }

    @Test
    void systemPromptIsMergedIntoSingleUserMessage() {
        var body = client("m", "", "").buildChatRequestBody("SYSTEM", "USER", null, 100);
        assertThat(body.get("messages")).isEqualTo(List.of(Map.of("role", "user", "content", "SYSTEM\n\nUSER")));
    }

    @Test
    void mistralTemplateOnlyForMistralFamily() {
        assertThat(client("saullm-54b", "", "").usesMistralTemplate()).isTrue();
        assertThat(client("mistralai/Mistral-7B-Instruct-v0.2", "", "").usesMistralTemplate()).isTrue();
        assertThat(client("qwen/qwen3.8-27b", "", "").usesMistralTemplate()).isFalse();
        assertThat(client("meta/muse-glimmer-30b", "", "").usesMistralTemplate()).isFalse();
    }
}
