package com.legalpartner.service.extraction;

import com.legalpartner.model.dto.extraction.ExtractionEntry;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Ranks extraction entries by importance.
 * Deterministic overlay: CRITICAL_FIELDS → always HIGH, COMMERCIAL/LEGAL → boost.
 * LLM ranks the rest. Cap HIGH fields at max 7.
 */
@Component
@Slf4j
public class ImportanceRanker {

    private final ChatLanguageModel jsonChatModel;
    private final ConsistencyChecker consistencyChecker;

    private static final Set<String> BOOST_BUCKETS = Set.of("COMMERCIAL", "LEGAL");
    private static final int MAX_HIGH = 7;

    public ImportanceRanker(@Qualifier("jsonChatModel") ChatLanguageModel jsonChatModel,
                            ConsistencyChecker consistencyChecker) {
        this.jsonChatModel = jsonChatModel;
        this.consistencyChecker = consistencyChecker;
    }

    public List<ExtractionEntry> rank(List<ExtractionEntry> entries) {
        Set<String> criticalFields = consistencyChecker.getCriticalFields();

        // Step 1: Rule-based ranking
        List<ExtractionEntry> ranked = new ArrayList<>();
        List<ExtractionEntry> needLlmRanking = new ArrayList<>();

        for (ExtractionEntry e : entries) {
            if (e.reasonCode() != null && e.value() == null) {
                // Gap entries get LOW importance unless they're critical missing fields
                String imp = criticalFields.contains(e.canonicalField()) ? ExtractionEntry.HIGH : ExtractionEntry.LOW;
                ranked.add(e.withImportance(imp));
            } else if (criticalFields.contains(e.canonicalField())) {
                ranked.add(e.withImportance(ExtractionEntry.HIGH));
            } else if (BOOST_BUCKETS.contains(e.bucket())) {
                ranked.add(e.withImportance(ExtractionEntry.MEDIUM)); // Boosted, may become HIGH from LLM
                needLlmRanking.add(e);
            } else {
                needLlmRanking.add(e);
                ranked.add(e); // Placeholder — will be updated
            }
        }

        // Step 2: LLM ranking for non-critical fields
        if (!needLlmRanking.isEmpty()) {
            Map<String, String> llmRankings = getLlmRankings(needLlmRanking);
            ranked = ranked.stream().map(e -> {
                if (criticalFields.contains(e.canonicalField())) return e; // Don't override critical
                String llmRank = llmRankings.get(e.canonicalField() != null ? e.canonicalField() : e.rawField());
                if (llmRank != null) {
                    // For COMMERCIAL/LEGAL bucket, take max of boost and LLM
                    if (BOOST_BUCKETS.contains(e.bucket())) {
                        return e.withImportance(maxImportance(e.importance(), llmRank));
                    }
                    return e.withImportance(llmRank);
                }
                return e.importance() == null ? e.withImportance(ExtractionEntry.MEDIUM) : e;
            }).collect(Collectors.toList());
        }

        // Step 3: Cap HIGH fields at MAX_HIGH
        long highCount = ranked.stream().filter(e -> ExtractionEntry.HIGH.equals(e.importance())).count();
        if (highCount > MAX_HIGH) {
            // Demote non-critical HIGH fields to MEDIUM (keep criticals)
            int demoteCount = (int) (highCount - MAX_HIGH);
            List<ExtractionEntry> finalRanked = new ArrayList<>();
            for (ExtractionEntry e : ranked) {
                if (ExtractionEntry.HIGH.equals(e.importance())
                        && !criticalFields.contains(e.canonicalField())
                        && demoteCount > 0) {
                    finalRanked.add(e.withImportance(ExtractionEntry.MEDIUM));
                    demoteCount--;
                } else {
                    finalRanked.add(e);
                }
            }
            ranked = finalRanked;
        }

        return ranked;
    }

    private Map<String, String> getLlmRankings(List<ExtractionEntry> entries) {
        Map<String, String> rankings = new HashMap<>();
        try {
            String fieldList = entries.stream()
                    .filter(e -> e.value() != null)
                    .map(e -> (e.canonicalField() != null ? e.canonicalField() : e.rawField()) + ": " + truncate(e.value(), 60))
                    .collect(Collectors.joining("\n"));

            if (fieldList.isBlank()) return rankings;

            String prompt = """
                    Rate the importance of each contract term for legal review.
                    HIGH: critical for deal evaluation, risk, or compliance.
                    MEDIUM: useful context.
                    LOW: administrative or boilerplate.

                    Terms:
                    """ + fieldList + """

                    Output JSON only:
                    {"rankings": [{"field": "field_name", "importance": "HIGH|MEDIUM|LOW"}]}
                    """;

            String response = jsonChatModel.generate(UserMessage.from(prompt)).content().text();
            // Parse JSON response
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(response.contains("{") ? response.substring(response.indexOf('{')) : response);
            for (var item : node.path("rankings")) {
                rankings.put(item.path("field").asText(), item.path("importance").asText("MEDIUM"));
            }
        } catch (Exception e) {
            log.warn("LLM importance ranking failed: {} — defaulting to MEDIUM", e.getMessage());
        }
        return rankings;
    }

    private String maxImportance(String a, String b) {
        int aVal = "HIGH".equals(a) ? 3 : "MEDIUM".equals(a) ? 2 : 1;
        int bVal = "HIGH".equals(b) ? 3 : "MEDIUM".equals(b) ? 2 : 1;
        return Math.max(aVal, bVal) >= 3 ? "HIGH" : Math.max(aVal, bVal) >= 2 ? "MEDIUM" : "LOW";
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
