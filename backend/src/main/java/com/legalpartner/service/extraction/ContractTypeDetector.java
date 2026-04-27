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

    private record TypeSignals(String type, List<String> strong, List<String> weak) {}

    private static final List<TypeSignals> TYPE_DEFINITIONS = List.of(
            new TypeSignals("NDA",
                    List.of("non-disclosure agreement", "confidentiality agreement", "mutual nda"),
                    List.of("confidential information", "receiving party", "disclosing party")),
            new TypeSignals("SAAS",
                    List.of("software as a service", "saas agreement", "subscription agreement"),
                    List.of("subscription", "uptime", "service level", "cloud", "hosted")),
            new TypeSignals("SOFTWARE_LICENSE",
                    List.of("software license agreement", "license agreement", "perpetual license"),
                    List.of("license fee", "licensor", "licensee", "software")),
            new TypeSignals("EMPLOYMENT",
                    List.of("employment agreement", "offer letter", "employment contract"),
                    List.of("employee", "employer", "salary", "probation", "termination of employment")),
            new TypeSignals("MSA",
                    List.of("master services agreement", "master agreement", "framework agreement"),
                    List.of("statement of work", "sow", "services", "professional services")),
            new TypeSignals("SUPPLY",
                    List.of("supply agreement", "purchase agreement", "procurement agreement"),
                    List.of("delivery", "supplier", "purchase order", "goods")),
            new TypeSignals("IP_LICENSE",
                    List.of("intellectual property license", "patent license", "ip license agreement"),
                    List.of("royalty", "patent", "trademark", "copyright license")),
            new TypeSignals("LEASE",
                    List.of("lease agreement", "rental agreement", "commercial lease"),
                    List.of("landlord", "tenant", "rent", "premises"))
    );

    public ContractTypeDetection detect(String fullText) {
        String lower = fullText.toLowerCase();
        String bestType = "_default";
        double bestScore = 0;
        List<String> bestSignals = List.of();

        for (TypeSignals td : TYPE_DEFINITIONS) {
            List<String> matchedSignals = new ArrayList<>();
            double score = 0;
            double maxPossible = td.strong.size() * 2.0 + td.weak.size();

            for (String kw : td.strong) {
                if (lower.contains(kw)) {
                    score += 2;
                    matchedSignals.add(kw);
                }
            }
            for (String kw : td.weak) {
                if (lower.contains(kw)) {
                    score += 1;
                    matchedSignals.add(kw);
                }
            }

            double confidence = maxPossible > 0 ? score / maxPossible : 0;
            if (confidence > bestScore) {
                bestScore = confidence;
                bestType = td.type;
                bestSignals = matchedSignals;
            }
        }

        // Minimum threshold — at least one strong keyword or two weak ones
        if (bestScore < 0.15) {
            bestType = "_default";
            bestScore = 0;
            bestSignals = List.of();
        }

        log.info("Contract type detection: {} (confidence={}, signals={})", bestType, String.format("%.2f", bestScore), bestSignals);
        return new ContractTypeDetection(bestType, bestScore, bestSignals);
    }
}
