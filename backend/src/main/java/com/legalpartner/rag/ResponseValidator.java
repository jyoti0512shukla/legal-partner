package com.legalpartner.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class ResponseValidator {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    public JsonNode parseAndValidate(String rawResponse) {
        String cleaned = rawResponse.trim();

        // Try direct parse
        JsonNode node = tryParse(cleaned);
        if (node != null) return node;

        // Try extracting from code fences
        Matcher m = JSON_BLOCK.matcher(cleaned);
        if (m.find()) {
            node = tryParse(m.group(1));
            if (node != null) return node;
        }

        // Try finding first { to last }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            node = tryParse(cleaned.substring(start, end + 1));
            if (node != null) return node;
        }

        log.warn("Failed to parse LLM response as JSON: {}", cleaned.substring(0, Math.min(200, cleaned.length())));
        return null;
    }

    public String calibrateConfidence(
            List<EmbeddingMatch<TextSegment>> usedChunks,
            long verifiedCitations,
            long totalCitations
    ) {
        if (usedChunks.isEmpty()) return "LOW";
        // If LLM explicitly reported insufficient context, trust it
        return computeConfidenceScore(usedChunks, verifiedCitations, totalCitations);
    }

    // New overload that takes the answer text
    public String calibrateConfidence(
            String answerText,
            List<EmbeddingMatch<TextSegment>> usedChunks,
            long verifiedCitations,
            long totalCitations
    ) {
        if (usedChunks.isEmpty()) return "LOW";
        if (answerText != null) {
            String lower = answerText.toLowerCase();
            if (lower.contains("insufficient context") || lower.contains("not in the context")
                    || lower.contains("cannot find") || lower.contains("no relevant")) {
                return "LOW";
            }
        }
        return computeConfidenceScore(usedChunks, verifiedCitations, totalCitations);
    }

    private String computeConfidenceScore(
            List<EmbeddingMatch<TextSegment>> usedChunks,
            long verifiedCitations,
            long totalCitations
    ) {
        double avgSimilarity = usedChunks.stream()
                .mapToDouble(EmbeddingMatch::score)
                .average()
                .orElse(0.0);

        long highRelevance = usedChunks.stream()
                .filter(m -> m.score() > 0.7)
                .count();
        double contextCoverage = (double) highRelevance / usedChunks.size();

        double citationRate = totalCitations > 0
                ? (double) verifiedCitations / totalCitations
                : 1.0;

        double confidence = (0.4 * contextCoverage) + (0.3 * avgSimilarity) + (0.3 * citationRate);

        if (confidence > 0.75) return "HIGH";
        if (confidence > 0.50) return "MEDIUM";
        return "LOW";
    }

    public List<String> checkFaithfulness(String answer, List<EmbeddingMatch<TextSegment>> chunks) {
        List<String> warnings = new ArrayList<>();
        String lower = answer.toLowerCase();

        Pattern sectionRef = Pattern.compile("(?i)(?:Section|Clause|Article)\\s+(\\d+(?:\\.\\d+)*)");
        Matcher m = sectionRef.matcher(answer);
        while (m.find()) {
            String ref = m.group();
            boolean found = chunks.stream().anyMatch(c -> {
                String path = c.embedded().metadata().getString("section_path");
                return path != null && path.toLowerCase().contains(m.group(1));
            });
            if (!found) {
                warnings.add("Reference '" + ref + "' could not be verified against source documents");
            }
        }
        return warnings;
    }

    private JsonNode tryParse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
