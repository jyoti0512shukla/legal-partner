package com.legalpartner.service.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.PromptRepository;
import com.legalpartner.model.entity.learning.DraftingInsight;
import com.legalpartner.repository.learning.DraftingInsightRepository;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Learned drafting preferences — the firm's evolving playbook.
 *
 * <ul>
 *   <li><b>Reflector</b> (CIPHER, Gao et al. NeurIPS 2024): an LLM turns one significant
 *       lawyer edit into 0–3 short, generalised drafting rules.</li>
 *   <li><b>Curator</b> (ACE, Zhang et al. 2025): each rule is merged deterministically into the
 *       playbook — a near-duplicate of an existing bullet adds evidence, otherwise a new PROPOSED
 *       bullet is created. The playbook is never rewritten wholesale.</li>
 *   <li><b>Activation</b>: PROPOSED → ACTIVE once supported by N distinct documents (or a lawyer).</li>
 *   <li><b>Outcome counters</b>: when a clause drafted with bullets is finally signed, low edit
 *       ratio → helpful, high → harmful; repeatedly harmful bullets retire themselves.</li>
 * </ul>
 */
@Service
@Slf4j
public class InsightService {

    public static final String PROPOSED = "PROPOSED";
    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DraftingInsightRepository repo;
    private final ChatLanguageModel jsonChatModel;
    private final PromptRepository prompts;
    private final LearningConfig config;

    public InsightService(DraftingInsightRepository repo,
                          @Qualifier("jsonChatModel") ChatLanguageModel jsonChatModel,
                          PromptRepository prompts, LearningConfig config) {
        this.repo = repo;
        this.jsonChatModel = jsonChatModel;
        this.prompts = prompts;
        this.config = config;
    }

    // ── Reflector ──────────────────────────────────────────────────────────────

    /** Infer preferences from one edit and curate them into the playbook. Returns the touched insights. */
    @Transactional
    public List<DraftingInsight> reflectOnEdit(String clauseKey, String contractType, String aiText,
                                               String lawyerText, UUID documentId) {
        List<String> rules = inferPreferences(clauseKey, contractType, aiText, lawyerText);
        List<DraftingInsight> touched = new ArrayList<>();
        for (String rule : rules) touched.add(curate(clauseKey, contractType, rule, documentId));
        log.info("Reflector [{}/{}]: {} preference(s) from edit on {}", clauseKey, contractType, rules.size(), documentId);
        return touched;
    }

    List<String> inferPreferences(String clauseKey, String contractType, String aiText, String lawyerText) {
        try {
            String prompt = String.format(prompts.get("LEARNING_REFLECT_EDIT"), clauseKey,
                    contractType == null ? "contract" : contractType, truncate(aiText, 6000), truncate(lawyerText, 6000));
            String raw = jsonChatModel.generate(UserMessage.from(prompt)).content().text();
            return parsePreferences(raw);
        } catch (Exception e) {
            log.warn("Reflector failed for {}: {}", clauseKey, e.getMessage());
            return List.of();
        }
    }

    static List<String> parsePreferences(String raw) {
        if (raw == null) return List.of();
        try {
            int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
            if (s < 0 || e <= s) return List.of();
            JsonNode node = MAPPER.readTree(raw.substring(s, e + 1)).path("preferences");
            List<String> out = new ArrayList<>();
            for (JsonNode n : node) {
                String rule = n.asText("").strip();
                if (rule.length() >= 8 && rule.length() <= 300 && out.size() < 3) out.add(rule);
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    // ── Curator ────────────────────────────────────────────────────────────────

    /** Merge one rule into the playbook (dedupe by rule similarity) and apply activation. */
    @Transactional
    public DraftingInsight curate(String clauseKey, String contractType, String rule, UUID documentId) {
        Optional<DraftingInsight> match = repo.findByClauseKey(clauseKey).stream()
                .filter(i -> !RETIRED.equals(i.getStatus()))
                .filter(i -> sameScope(i.getContractType(), contractType))
                .filter(i -> TextSimilarity.ruleSimilarity(i.getContent(), rule) >= config.getInsightSimilarity())
                .max(Comparator.comparingDouble(i -> TextSimilarity.ruleSimilarity(i.getContent(), rule)));
        DraftingInsight insight = match.orElseGet(() -> DraftingInsight.builder()
                .clauseKey(clauseKey).contractType(contractType).content(rule)
                .status(PROPOSED).source("EDIT_REFLECTION").build());
        Set<String> docs = parseIds(insight.getEvidenceDocs());
        if (documentId != null) docs.add(documentId.toString());
        insight.setEvidenceDocs(String.join(",", docs));
        insight.setEvidenceCount(insight.getEvidenceCount() + 1);
        if (PROPOSED.equals(insight.getStatus()) && config.isInsightAutoActivate()
                && docs.size() >= config.getInsightAutoActivateDocs()) {
            insight.setStatus(ACTIVE);
            insight.setApprovedBy("auto:" + docs.size() + "-documents");
            log.info("Insight auto-activated [{}]: {}", clauseKey, rule);
        }
        insight.setUpdatedAt(Instant.now());
        return repo.save(insight);
    }

    private static boolean sameScope(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    // ── Use at drafting time ───────────────────────────────────────────────────

    /** Active insights for a clause: contract-type-specific first, then general; best track record first. */
    public List<DraftingInsight> activeFor(String clauseKey, String contractType) {
        if (!config.isEnabled()) return List.of();
        return repo.findByClauseKeyAndStatus(clauseKey, ACTIVE).stream()
                .filter(i -> i.getContractType() == null || i.getContractType().equalsIgnoreCase(contractType))
                .sorted(Comparator
                        .comparing((DraftingInsight i) -> i.getContractType() == null ? 1 : 0)
                        .thenComparing(i -> -(i.getHelpfulCount() - i.getHarmfulCount()))
                        .thenComparing(i -> -i.getEvidenceCount()))
                .limit(config.getInsightsPerClause())
                .toList();
    }

    /** Prompt block injected into LLM clause generation. */
    public String promptBlock(List<DraftingInsight> insights) {
        if (insights == null || insights.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(prompts.get("LEARNING_INSIGHTS_HEADER"));
        for (DraftingInsight i : insights) sb.append("- ").append(i.getContent()).append("\n");
        return sb.toString();
    }

    // ── Outcome counters (delayed signal) ──────────────────────────────────────

    /** Credit or blame the insights that were in the prompt, given the final edit ratio. */
    @Transactional
    public void recordOutcome(Set<UUID> insightIds, double finalEditRatio) {
        if (insightIds == null || insightIds.isEmpty()) return;
        for (DraftingInsight i : repo.findAllById(insightIds)) {
            if (finalEditRatio <= config.getHelpfulEditRatio()) i.setHelpfulCount(i.getHelpfulCount() + 1);
            else if (finalEditRatio >= config.getHarmfulEditRatio()) i.setHarmfulCount(i.getHarmfulCount() + 1);
            else continue;
            if (ACTIVE.equals(i.getStatus()) && i.getHarmfulCount() >= config.getInsightRetireHarmful()
                    && i.getHarmfulCount() > 2 * i.getHelpfulCount()) {
                i.setStatus(RETIRED);
                log.info("Insight auto-retired (harmful {} vs helpful {}): {}", i.getHarmfulCount(), i.getHelpfulCount(), i.getContent());
            }
            i.setUpdatedAt(Instant.now());
            repo.save(i);
        }
    }

    // ── Lawyer actions ─────────────────────────────────────────────────────────

    @Transactional
    public DraftingInsight setStatus(UUID id, String status, String user) {
        DraftingInsight i = repo.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("Insight not found"));
        i.setStatus(status);
        if (ACTIVE.equals(status)) i.setApprovedBy(user);
        i.setUpdatedAt(Instant.now());
        return repo.save(i);
    }

    @Transactional
    public DraftingInsight createManual(String clauseKey, String contractType, String content, String user) {
        return repo.save(DraftingInsight.builder().clauseKey(clauseKey).contractType(contractType)
                .content(content.strip()).status(ACTIVE).source("MANUAL").approvedBy(user).evidenceCount(0).build());
    }

    public List<DraftingInsight> list(String status) {
        return status == null ? repo.findAll() : repo.findByStatusOrderByUpdatedAtDesc(status);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    public static Set<String> parseIds(String csv) {
        if (csv == null || csv.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
