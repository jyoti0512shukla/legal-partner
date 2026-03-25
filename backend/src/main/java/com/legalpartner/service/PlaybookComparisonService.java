package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.entity.*;
import com.legalpartner.model.enums.FindingSeverity;
import com.legalpartner.model.enums.FindingType;
import com.legalpartner.rag.DocumentFullTextRetriever;
import com.legalpartner.repository.PlaybookPositionRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class PlaybookComparisonService {

    private final PlaybookPositionRepository positionRepo;
    private final ChatLanguageModel jsonChatModel;
    private final DocumentFullTextRetriever fullTextRetriever;
    private final ObjectMapper objectMapper;

    public PlaybookComparisonService(PlaybookPositionRepository positionRepo,
                                      @Qualifier("jsonChatModel") ChatLanguageModel jsonChatModel,
                                      DocumentFullTextRetriever fullTextRetriever,
                                      ObjectMapper objectMapper) {
        this.positionRepo = positionRepo;
        this.jsonChatModel = jsonChatModel;
        this.fullTextRetriever = fullTextRetriever;
        this.objectMapper = objectMapper;
    }

    public List<MatterFinding> compareClauses(Matter matter, DocumentMetadata doc) {
        if (matter.getDefaultPlaybook() == null) return List.of();

        List<PlaybookPosition> positions = positionRepo.findByPlaybookId(matter.getDefaultPlaybook().getId());
        if (positions.isEmpty()) return List.of();

        String fullText = fullTextRetriever.retrieveFullText(doc.getId());
        if (fullText == null || fullText.isBlank()) return List.of();
        String truncated = fullText.length() > 8000 ? fullText.substring(0, 8000) : fullText;

        List<MatterFinding> findings = new ArrayList<>();
        for (PlaybookPosition position : positions) {
            try {
                MatterFinding finding = comparePosition(position, truncated, matter, doc);
                if (finding != null) findings.add(finding);
            } catch (Exception e) {
                log.warn("Playbook comparison failed for clause {}: {}", position.getClauseType(), e.getMessage());
            }
        }
        return findings;
    }

    private MatterFinding comparePosition(PlaybookPosition position, String contractText,
                                           Matter matter, DocumentMetadata doc) {
        String prompt = String.format("""
                Analyze this contract against the firm's standard position.

                Clause Type: %s
                Firm's Standard Position: %s
                Minimum Acceptable: %s
                Non-negotiable: %s

                Contract text:
                %s

                Respond ONLY with valid JSON:
                {"verdict": "MATCHES|DEVIATES|MISSING", "severity": "HIGH|MEDIUM|LOW", "explanation": "one sentence", "section_ref": "Section X.Y or MISSING"}

                Rules:
                - DEVIATES but meets minimum → MEDIUM
                - DEVIATES and below minimum → HIGH
                - Non-negotiable and DEVIATES → always HIGH
                - MISSING entirely → HIGH
                - MATCHES → verdict is MATCHES
                """,
                position.getClauseType(),
                position.getStandardPosition(),
                position.getMinimumAcceptable() != null ? position.getMinimumAcceptable() : "N/A",
                position.isNonNegotiable(),
                contractText);

        String response = jsonChatModel.generate(prompt);
        JsonNode node;
        try {
            node = objectMapper.readTree(response);
        } catch (Exception e) {
            // Try to extract JSON from response
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try { node = objectMapper.readTree(response.substring(start, end + 1)); }
                catch (Exception e2) { return null; }
            } else { return null; }
        }

        String verdict = node.path("verdict").asText("MATCHES");
        if ("MATCHES".equalsIgnoreCase(verdict)) return null;

        String severityStr = node.path("severity").asText("MEDIUM");
        FindingSeverity severity;
        try { severity = FindingSeverity.valueOf(severityStr.toUpperCase()); }
        catch (Exception e) { severity = FindingSeverity.MEDIUM; }

        if (position.isNonNegotiable() && !"MATCHES".equalsIgnoreCase(verdict)) {
            severity = FindingSeverity.HIGH;
        }

        FindingType type = position.isNonNegotiable()
                ? FindingType.PLAYBOOK_NON_NEGOTIABLE
                : FindingType.PLAYBOOK_DEVIATION;

        return MatterFinding.builder()
                .matter(matter)
                .document(doc)
                .findingType(type)
                .severity(severity)
                .clauseType(position.getClauseType())
                .title(position.getClauseType() + ": " + verdict)
                .description(node.path("explanation").asText("Clause deviates from firm standard"))
                .sectionRef(node.path("section_ref").asText(null))
                .playbookPosition(position)
                .build();
    }
}
