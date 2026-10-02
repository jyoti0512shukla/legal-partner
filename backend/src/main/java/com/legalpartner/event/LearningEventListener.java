package com.legalpartner.event;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.DocumentVersion;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.DocumentVersionRepository;
import com.legalpartner.service.learning.EditCaptureService;
import com.legalpartner.service.learning.ExposureService;
import com.legalpartner.service.learning.FirmClauseBankService;
import com.legalpartner.service.learning.LearningConfig;
import com.legalpartner.service.learning.ReviewCalibrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Feeds document lifecycle events into the firm learning loop
 * (docs/LEARNING_LOOP_ARCHITECTURE.md). Runs after the triggering transaction commits and
 * off the request thread; a learning failure is logged and never affects the user action.
 *
 * <ul>
 *   <li>indexed  → observe the document's clauses for the firm clause bank</li>
 *   <li>revised  → capture the lawyer's per-clause edits of an AI draft (firm edit sources only)</li>
 *   <li>executed → final edit capture (insight outcomes) and re-observe as a signed contract</li>
 *   <li>deleted  → forget everything learned from the document</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LearningEventListener {

    private final LearningConfig config;
    private final FirmClauseBankService clauseBank;
    private final EditCaptureService editCapture;
    private final ExposureService exposures;
    private final ReviewCalibrationService calibration;
    private final DocumentMetadataRepository documents;
    private final DocumentVersionRepository versions;

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onIndexed(DocumentIndexedEvent e) {
        run("observe " + e.documentId(), () -> clauseBank.observeDocument(e.documentId()));
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onRevised(DocumentRevisedEvent e) {
        if (!config.isFirmEdit(e.source())) return;
        run("capture edits " + e.documentId(),
                () -> editCapture.capture(e.documentId(), e.storedPath(), e.versionNumber(), false));
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onExecuted(DocumentExecutedEvent e) {
        run("final capture " + e.documentId(), () -> {
            String path = latestPath(e.documentId());
            if (path != null) editCapture.capture(e.documentId(), path, null, true);
        });
        run("observe signed " + e.documentId(), () -> clauseBank.observeDocument(e.documentId()));
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onDeleted(DocumentDeletedEvent e) {
        UUID id = e.documentId();
        run("forget observations " + id, () -> clauseBank.forgetDocument(id));
        run("forget edits " + id, () -> editCapture.forgetDocument(id));
        run("forget exposures " + id, () -> exposures.forgetDocument(id));
        run("forget disputes " + id, () -> calibration.forgetDocument(id));
    }

    /** Stored path of the newest version (the signed text), else the document's own file. */
    private String latestPath(UUID documentId) {
        return versions.findByDocumentIdOrderByVersionNumberDesc(documentId).stream().findFirst()
                .map(DocumentVersion::getStoredPath)
                .orElseGet(() -> documents.findById(documentId).map(DocumentMetadata::getStoredPath).orElse(null));
    }

    private void run(String what, Runnable step) {
        if (!config.isEnabled()) return;
        try {
            step.run();
        } catch (Exception ex) {
            log.warn("Learning: {} failed: {}", what, ex.getMessage());
        }
    }
}
