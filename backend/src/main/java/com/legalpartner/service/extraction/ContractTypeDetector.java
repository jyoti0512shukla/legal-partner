package com.legalpartner.service.extraction;

import com.legalpartner.model.dto.extraction.ContractTypeDetection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Weighted keyword detection for contract type with confidence scoring.
 * Strong keywords score 2x, weak keywords score 1x.
 * Returns type + confidence + signals (which keywords matched).
 */
@Component
@Slf4j
public class ContractTypeDetector {

    /** Type signals and threshold from config/vocabulary.yml (contract_type_detection). */
    private final com.legalpartner.config.LegalVocabulary vocabulary;

    public ContractTypeDetector(com.legalpartner.config.LegalVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    public ContractTypeDetection detect(String fullText) {
        String lower = fullText.toLowerCase();
        String bestType = "_default";
        double bestScore = 0;
        List<String> bestSignals = List.of();

        for (var td : vocabulary.typeSignals()) {
            List<String> matchedSignals = new ArrayList<>();
            double score = 0;
            double maxPossible = td.strong().size() * 2.0 + td.weak().size();

            for (String kw : td.strong()) {
                if (lower.contains(kw)) {
                    score += 2;
                    matchedSignals.add(kw);
                }
            }
            for (String kw : td.weak()) {
                if (lower.contains(kw)) {
                    score += 1;
                    matchedSignals.add(kw);
                }
            }

            double confidence = maxPossible > 0 ? score / maxPossible : 0;
            if (confidence > bestScore) {
                bestScore = confidence;
                bestType = td.type();
                bestSignals = matchedSignals;
            }
        }

        // Minimum confidence (vocabulary.yml min_confidence)
        if (bestScore < vocabulary.typeMinConfidence()) {
            bestType = "_default";
            bestScore = 0;
            bestSignals = List.of();
        }

        log.info("Contract type detection: {} (confidence={}, signals={})", bestType, String.format("%.2f", bestScore), bestSignals);
        return new ContractTypeDetection(bestType, bestScore, bestSignals);
    }
}
