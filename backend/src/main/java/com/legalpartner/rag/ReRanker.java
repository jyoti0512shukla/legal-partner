package com.legalpartner.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ReRanker {

    @Value("${legalpartner.rag.vector-weight:0.7}")
    private double vectorWeight;

    @Value("${legalpartner.rag.keyword-weight:0.2}")
    private double keywordWeight;

    @Value("${legalpartner.rag.recency-weight:0.1}")
    private double recencyWeight;

    public List<EmbeddingMatch<TextSegment>> rerank(
            List<EmbeddingMatch<TextSegment>> candidates,
            String originalQuery,
            int topK
    ) {
        Set<String> queryKeywords = Arrays.stream(originalQuery.toLowerCase().split("\\s+"))
                .filter(w -> w.length() > 3)
                .collect(Collectors.toSet());

        int currentYear = Year.now().getValue();

        List<ScoredMatch> scored = candidates.stream().map(match -> {
            double vectorScore = match.score();
            double keywordScore = computeKeywordOverlap(match.embedded().text(), queryKeywords);
            double recencyScore = computeRecencyBoost(match.embedded().metadata().getString("year"), currentYear);
            double finalScore = (vectorWeight * vectorScore) + (keywordWeight * keywordScore) + (recencyWeight * recencyScore);
            return new ScoredMatch(match, finalScore);
        }).sorted(Comparator.comparingDouble(ScoredMatch::score).reversed()).toList();

        return scored.stream()
                .limit(topK)
                .map(ScoredMatch::match)
                .toList();
    }

    private double computeKeywordOverlap(String text, Set<String> queryKeywords) {
        if (queryKeywords.isEmpty()) return 0.0;
        String lower = text.toLowerCase();
        long found = queryKeywords.stream().filter(lower::contains).count();
        return (double) found / queryKeywords.size();
    }

    private double computeRecencyBoost(String yearStr, int currentYear) {
        if (yearStr == null) return 0.5;
        try {
            int docYear = Integer.parseInt(yearStr);
            int diff = currentYear - docYear;
            return Math.max(0, 1.0 - (diff * 0.1));
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }

    private record ScoredMatch(EmbeddingMatch<TextSegment> match, double score) {}
}
