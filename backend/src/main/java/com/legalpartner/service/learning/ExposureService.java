package com.legalpartner.service.learning;

import com.legalpartner.model.entity.learning.DraftClauseExposure;
import com.legalpartner.repository.learning.DraftClauseExposureRepository;
import com.legalpartner.service.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exposure log: what each drafted article was built from (firm clause, shipped golden clause
 * or LLM, plus the learned insights in its prompt). Every later learning step joins on this,
 * so outcomes are credited to what the lawyer actually saw.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExposureService {

    public static final String FIRM_BANK = "FIRM_BANK";
    public static final String GOLDEN = "GOLDEN";
    public static final String LLM = "LLM";

    private final DraftClauseExposureRepository repo;
    private final EncryptionService encryption;
    private final LearningConfig config;

    public record Exposure(String clauseKey, int article, String title, String provenance,
                           UUID firmClauseId, Collection<UUID> insightIds, String text) {}

    @Transactional
    public void record(UUID documentId, String templateId, String contractType, List<Exposure> exposures) {
        if (!config.isEnabled() || exposures == null || exposures.isEmpty()) return;
        repo.deleteByDocumentId(documentId); // a re-run of the same draft replaces its log
        for (Exposure e : exposures) {
            repo.save(DraftClauseExposure.builder()
                    .documentId(documentId).clauseKey(e.clauseKey()).article(e.article()).title(e.title())
                    .templateId(templateId).contractType(contractType).provenance(e.provenance())
                    .firmClauseId(e.firmClauseId())
                    .insightIds(e.insightIds() == null ? null
                            : e.insightIds().stream().map(UUID::toString).collect(Collectors.joining(",")))
                    .generatedText(e.text() == null ? null : encryption.encrypt(e.text()))
                    .build());
        }
        log.info("Exposure log: {} article(s) for draft {}", exposures.size(), documentId);
    }

    public List<DraftClauseExposure> forDocument(UUID documentId) {
        return repo.findByDocumentIdOrderByArticle(documentId);
    }

    @Transactional
    public void forgetDocument(UUID documentId) {
        repo.deleteByDocumentId(documentId);
    }

    public String decrypt(DraftClauseExposure e) {
        return e.getGeneratedText() == null ? "" : encryption.decrypt(e.getGeneratedText());
    }
}
