package com.legalpartner.service.learning;

import com.legalpartner.model.entity.learning.ClauseEdit;
import com.legalpartner.model.entity.learning.DraftClauseExposure;
import com.legalpartner.repository.learning.ClauseEditRepository;
import com.legalpartner.service.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Captures how lawyers change AI drafts, clause by clause.
 *
 * On every saved version (early, noisy signal): align the lawyer's articles with the exposure
 * log, store the edit ratio, and send the first significant edit of each clause to the
 * Reflector. On signing (delayed, reliable outcome): mark edits final and credit or blame the
 * learned insights that were in the clause's prompt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EditCaptureService {

    private final ExposureService exposures;
    private final ClauseEditRepository edits;
    private final DocumentTextReader textReader;
    private final EncryptionService encryption;
    private final InsightService insights;
    private final LearningConfig config;

    public record Capture(String clauseKey, double editRatio, boolean reflected) {}

    public List<Capture> capture(UUID documentId, String storedPath, Integer versionNumber, boolean isFinal) {
        if (!config.isEnabled()) return List.of();
        List<DraftClauseExposure> exposed = exposures.forDocument(documentId);
        if (exposed.isEmpty()) return List.of(); // not an AI draft (or drafted before the learning loop)
        String text = textReader.read(storedPath);
        if (text.isBlank()) return List.of();
        List<ArticleSplitter.Article> articles = ArticleSplitter.split(text);

        List<Capture> out = new java.util.ArrayList<>();
        for (DraftClauseExposure e : exposed) {
            String aiText = exposures.decrypt(e);
            String finalText = align(e, articles).map(ArticleSplitter.Article::body).orElse("");
            double ratio = TextSimilarity.editRatio(aiText, finalText);

            ClauseEdit edit = edits.findByDocumentIdAndClauseKey(documentId, e.getClauseKey())
                    .orElseGet(() -> ClauseEdit.builder().documentId(documentId).clauseKey(e.getClauseKey()).build());
            edit.setArticle(e.getArticle());
            edit.setTemplateId(e.getTemplateId());
            edit.setAiText(encryption.encrypt(aiText));
            edit.setFinalText(encryption.encrypt(finalText));
            edit.setEditRatio(ratio);
            edit.setVersionNumber(versionNumber);
            edit.setFinal(edit.isFinal() || isFinal);
            edit.setCapturedAt(Instant.now());

            boolean reflectedNow = false;
            if (!edit.isReflected() && !finalText.isBlank() && ratio >= config.getSignificantEditRatio()) {
                insights.reflectOnEdit(e.getClauseKey(), e.getContractType(), aiText, finalText, documentId);
                edit.setReflected(true);
                reflectedNow = true;
            }
            edits.save(edit);
            if (isFinal) insights.recordOutcome(parseUuids(e.getInsightIds()), ratio);
            out.add(new Capture(e.getClauseKey(), ratio, reflectedNow));
        }
        log.info("Edit capture for {} (v{}, final={}): {}", documentId, versionNumber, isFinal,
                out.stream().map(c -> c.clauseKey() + "=" + String.format("%.2f", c.editRatio())).collect(Collectors.joining(", ")));
        return out;
    }

    /** Same title first (the lawyer may renumber), then same article number. */
    static Optional<ArticleSplitter.Article> align(DraftClauseExposure e, List<ArticleSplitter.Article> articles) {
        String title = TextSimilarity.normTitle(e.getTitle());
        return articles.stream().filter(a -> !title.isEmpty() && TextSimilarity.normTitle(a.title()).equals(title)).findFirst()
                .or(() -> articles.stream().filter(a -> a.number() == e.getArticle()).findFirst());
    }

    static Set<UUID> parseUuids(String csv) {
        Set<UUID> out = new HashSet<>();
        for (String s : InsightService.parseIds(csv)) {
            try { out.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    @org.springframework.transaction.annotation.Transactional
    public void forgetDocument(UUID documentId) {
        edits.deleteByDocumentId(documentId);
    }
}
