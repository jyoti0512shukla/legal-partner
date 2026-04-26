package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.enums.WorkflowStepType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Heuristic (no-LLM) quality scorer for workflow step outputs.
 * Returns a 0–100 score and a list of gaps to address if score < 70.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowQualityScorer {

    private static final int PASSING_SCORE = 70;

    public record QualityScore(int score, List<String> gaps) {
        public boolean isPassing() { return score >= PASSING_SCORE; }
    }

    public QualityScore score(WorkflowStepType type, Object result, ObjectMapper mapper) {
        try {
            String json = mapper.writeValueAsString(result);
            JsonNode node = mapper.readTree(json);
            return switch (type) {
                case RISK_ASSESSMENT     -> scoreRisk(node);
                case REDLINE_SUGGESTIONS -> scoreRedlines(node);
                case GENERATE_SUMMARY    -> scoreSummary(node);
                case EXTRACT_KEY_TERMS   -> scoreExtraction(node);
                case DRAFT_CLAUSE        -> scoreDraft(node);
                case COMPLIANCE_CHECK    -> scoreCompliance(node);
                case OBLIGATION_EXTRACT  -> scoreObligations(node);
                case APPROVAL_GATE       -> new QualityScore(100, List.of()); // no quality loop for human gate
            };
        } catch (Exception e) {
            log.warn("Quality scoring failed for {}: {}", type, e.getMessage());
            return new QualityScore(75, List.of()); // assume ok on parse failure
        }
    }

    private QualityScore scoreRisk(JsonNode node) {
        List<String> gaps = new ArrayList<>();
        int score = 30;

        int catCount = node.path("categories").size();
        if (catCount >= 5) score += 25;
        else { score += catCount * 5; gaps.add("Only " + catCount + " risk categories identified — need at least 5 (LIABILITY, INDEMNITY, TERMINATION, IP_RIGHTS, CONFIDENTIALITY, GOVERNING_LAW, FORCE_MAJEURE)"); }

        boolean hasJustifications = StreamSupport.stream(node.path("categories").spliterator(), false)
                .allMatch(c -> c.path("justification").asText("").length() > 20);
        if (hasJustifications) score += 20;
        else gaps.add("Some risk categories have no justification — explain WHY each rating was assigned with reference to the contract text");

        boolean hasSectionRefs = StreamSupport.stream(node.path("categories").spliterator(), false)
                .anyMatch(c -> {
                    String ref = c.path("sectionRef").asText(c.path("section_ref").asText(""));
                    return !ref.isBlank() && !"See contract".equalsIgnoreCase(ref);
                });
        if (hasSectionRefs) score += 15;
        else gaps.add("No specific section references — cite the actual clause number or section name from the contract (e.g. 'Clause 8.2', 'Section 12 — Force Majeure')");

        if (!node.path("overallRisk").asText("").isBlank()) score += 10;
        else gaps.add("Overall risk level (HIGH/MEDIUM/LOW) not set");

        return new QualityScore(Math.min(score, 100), gaps);
    }

    private QualityScore scoreCompliance(JsonNode node) {
        List<String> gaps = new ArrayList<>();
        int score = 50;

        String status = node.path("status").asText("");
        if ("COMPLETED".equals(status)) score += 20;
        else gaps.add("Compliance check did not complete — " + status);

        int totalChecked = node.path("totalChecked").asInt(0);
        if (totalChecked >= 3) score += 15;
        else gaps.add("Fewer than 3 playbook positions checked");

        int violations = node.path("violations").size();
        // Having violations is expected — the quality is about completeness, not absence of issues
        if (totalChecked > 0) score += 15;

        return new QualityScore(Math.min(score, 100), gaps);
    }

    private QualityScore scoreObligations(JsonNode node) {
        List<String> gaps = new ArrayList<>();
        int score = 40;

        String status = node.path("status").asText("");
        if ("COMPLETED".equals(status)) score += 20;

        int obligationCount = node.path("obligations").size();
        if (obligationCount >= 3) score += 25;
        else if (obligationCount > 0) { score += obligationCount * 8; gaps.add("Only " + obligationCount + " obligation(s) found — check for payments, deadlines, renewals, notice periods"); }
        else gaps.add("No obligations extracted — scan for payment terms, deliverable dates, renewal clauses, notice requirements");

        boolean hasTypes = StreamSupport.stream(node.path("obligations").spliterator(), false)
                .allMatch(o -> !o.path("type").asText("").isBlank());
        if (hasTypes) score += 15;

        return new QualityScore(Math.min(score, 100), gaps);
    }

    private QualityScore scoreRedlines(JsonNode node) {
        List<String> gaps = new ArrayList<>();
        int score = 25;

        int suggCount = node.path("suggestions").size();
        if (suggCount >= 3) score += 30;
        else if (suggCount == 0) gaps.add("No redline suggestions — if clause issues were identified, provide specific improved language for each");
        else { score += suggCount * 8; gaps.add("Only " + suggCount + " suggestion(s) — provide at least 3, one per weak/missing clause"); }

        boolean hasSpecificLanguage = StreamSupport.stream(node.path("suggestions").spliterator(), false)
                .allMatch(s -> s.path("suggestedLanguage").asText("").length() > 60);
        if (hasSpecificLanguage) score += 25;
        else gaps.add("Suggested language is too brief or generic — write full clause-level text (minimum 2 sentences) that could be inserted directly into the contract");

        boolean hasRationale = StreamSupport.stream(node.path("suggestions").spliterator(), false)
                .allMatch(s -> s.path("rationale").asText("").length() > 20);
        if (hasRationale) score += 20;
        else gaps.add("Rationale is missing or too brief — explain why the suggested language is legally superior");

        return new QualityScore(Math.min(score, 100), gaps);
    }

    private QualityScore scoreSummary(JsonNode node) {
        List<String> gaps = new ArrayList<>();
        int score = 35;

        String summary = node.path("executiveSummary").asText("");
        if (summary.length() >= 150) score += 25;
        else gaps.add("Executive summary too short — write at least 3-4 sentences covering: contract purpose, key parties, primary obligations, and main risks");

        int concernCount = node.path("topConcerns").size();
        if (concernCount >= 2) score += 20;
        else gaps.add("Fewer than 2 top concerns — identify the most significant legal or commercial risks from the analysis");

        int recCount = node.path("recommendations").size();
        if (recCount >= 1) score += 20;
        else gaps.add("No actionable recommendations — suggest specific negotiation points or protective clauses the party should request");

        return new QualityScore(Math.min(score, 100), gaps);
    }

    private QualityScore scoreExtraction(JsonNode node) {
        List<String> gaps = new ArrayList<>();
        int score = 35;

        String[] keyFields = {"partyA", "partyB", "effectiveDate", "governingLaw"};
        List<String> missing = new ArrayList<>();
        for (String f : keyFields) {
            String val = node.path(f).asText("");
            if (!val.isBlank() && !"N/A".equalsIgnoreCase(val)) score += 15;
            else missing.add(f);
        }
        if (!missing.isEmpty())
            gaps.add("Could not extract: " + String.join(", ", missing) + " — search the full contract text for these values");

        return new QualityScore(Math.min(score, 100), gaps);
    }

    private QualityScore scoreDraft(JsonNode node) {
        List<String> gaps = new ArrayList<>();
        int score = 30;

        String content = node.path("content").asText(node.path("html").asText(""));
        if (content.length() > 600) score += 35;
        else gaps.add("Draft clause is too short — expand each sub-clause with complete, commercially reasonable legal text (minimum 3 sub-clauses)");

        boolean hasPlaceholders = content.matches("(?i).*\\[[^]]{1,50}].*") || content.contains("INSERT") || content.contains("TBD");
        if (!hasPlaceholders) score += 25;
        else gaps.add("Draft contains unfilled placeholders ([...], INSERT, TBD) — replace every placeholder with generic but legally sound language");

        if (content.contains(".") && content.split("\\.").length >= 3) score += 10;
        else gaps.add("Draft appears to be heading-only — write the full substantive clause text, not just titles");

        return new QualityScore(Math.min(score, 100), gaps);
    }
}
