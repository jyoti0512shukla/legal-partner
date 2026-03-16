package com.legalpartner.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ReRanker {

    // Document type authority order (higher index = more authoritative)
    private static final Map<String, Double> DOCTYPE_AUTHORITY = Map.of(
            "LEGISLATION", 1.0,
            "PRECEDENT", 0.8,
            "CONTRACT", 0.6,
            "OTHER", 0.4
    );

    // Legal acronyms that should NOT be filtered by length
    private static final Set<String> LEGAL_ACRONYMS = Set.of(
            "ip", "nda", "msa", "sow", "sla", "mou", "loi", "ica", "ipc", "crpc",
            "gst", "vat", "adr", "icc", "lcia", "siac", "cci", "sebi", "rbi"
    );

    @Value("${legalpartner.rag.vector-weight:0.7}")
    private double vectorWeight;

    @Value("${legalpartner.rag.keyword-weight:0.2}")
    private double keywordWeight;

    @Value("${legalpartner.rag.doctype-weight:0.1}")
    private double doctypeWeight;

    public List<EmbeddingMatch<TextSegment>> rerank(
            List<EmbeddingMatch<TextSegment>> candidates,
            String originalQuery,
            int topK
    ) {
        Set<String> queryKeywords = extractKeywords(originalQuery);

        List<ScoredMatch> scored = candidates.stream().map(match -> {
            double vectorScore = match.score();
            double keywordScore = computeKeywordOverlap(match.embedded().text(), queryKeywords);
            double doctypeScore = computeDoctypeAuthority(match.embedded().metadata().getString("document_type"));
            double finalScore = (vectorWeight * vectorScore) + (keywordWeight * keywordScore) + (doctypeWeight * doctypeScore);
            return new ScoredMatch(match, finalScore);
        }).sorted(Comparator.comparingDouble(ScoredMatch::score).reversed()).toList();

        // Apply diversity: cap at maxPerDoc chunks from any single document
        return applyDiversity(scored, topK);
    }

    private Set<String> extractKeywords(String query) {
        return Arrays.stream(query.toLowerCase().split("[\\s,;.!?]+"))
                .filter(w -> w.length() > 2 || LEGAL_ACRONYMS.contains(w))  // was > 3, now > 2 + acronyms
                .collect(Collectors.toSet());
    }

    private double computeKeywordOverlap(String text, Set<String> queryKeywords) {
        if (queryKeywords.isEmpty()) return 0.0;
        String lower = text.toLowerCase();
        long found = queryKeywords.stream().filter(lower::contains).count();
        return (double) found / queryKeywords.size();
    }

    private double computeDoctypeAuthority(String documentType) {
        if (documentType == null) return 0.5;
        return DOCTYPE_AUTHORITY.getOrDefault(documentType.toUpperCase(), 0.5);
    }

    private List<EmbeddingMatch<TextSegment>> applyDiversity(List<ScoredMatch> scored, int topK) {
        Map<String, Integer> docChunkCount = new HashMap<>();
        int maxPerDoc = Math.max(2, topK / 2);  // allow at most topK/2 chunks from one doc
        List<EmbeddingMatch<TextSegment>> result = new ArrayList<>();

        for (ScoredMatch sm : scored) {
            if (result.size() >= topK) break;
            String docId = sm.match().embedded().metadata().getString("document_id");
            String key = docId != null ? docId : "unknown";
            int count = docChunkCount.getOrDefault(key, 0);
            if (count < maxPerDoc) {
                result.add(sm.match());
                docChunkCount.put(key, count + 1);
            }
        }

        // If diversity filter left us short, fill with remaining
        if (result.size() < topK) {
            for (ScoredMatch sm : scored) {
                if (result.size() >= topK) break;
                if (!result.contains(sm.match())) {
                    result.add(sm.match());
                }
            }
        }

        return result;
    }

    private record ScoredMatch(EmbeddingMatch<TextSegment> match, double score) {}
}
