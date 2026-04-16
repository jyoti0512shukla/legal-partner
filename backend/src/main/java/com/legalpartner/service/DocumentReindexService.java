package com.legalpartner.service;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.repository.DocumentMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * One-shot re-processing of documents that were ingested BEFORE the
 * AnonymizationService landed. Reads each doc's raw file from disk, runs it
 * through the new anonymization pipeline, deletes the old raw embeddings,
 * and re-embeds the anonymized version.
 *
 * Safe to run multiple times — it only touches docs where
 * {@code is_anonymized=false}. Runs async so it doesn't block the admin
 * caller; check back via {@code document_metadata.is_anonymized}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentReindexService {

    private final DocumentMetadataRepository repository;
    private final DocumentService documentService;
    private final FileStorageService fileStorageService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Kick off reindex for every non-anonymized USER doc. Returns the count
     * of docs that were scheduled; actual processing runs in the @Async pool.
     */
    public int reindexAllNonAnonymized() {
        List<DocumentMetadata> docs = repository.findAll().stream()
                .filter(d -> !d.isAnonymized())
                .filter(d -> "USER".equalsIgnoreCase(d.getSource()) || "CLOUD".equalsIgnoreCase(d.getSource())
                        || "DRAFTED".equalsIgnoreCase(d.getSource()))
                .filter(d -> d.getStoredPath() != null)
                .toList();
        log.info("Reindex scheduled for {} non-anonymized docs", docs.size());
        for (DocumentMetadata doc : docs) {
            reindexOne(doc.getId());
        }
        return docs.size();
    }

    @Async
    public void reindexOne(UUID docId) {
        DocumentMetadata doc = repository.findById(docId).orElse(null);
        if (doc == null) {
            log.warn("Reindex: doc {} not found", docId);
            return;
        }
        if (doc.isAnonymized()) {
            log.debug("Reindex: doc {} already anonymized, skipping", docId);
            return;
        }
        if (doc.getStoredPath() == null || !fileStorageService.exists(doc.getStoredPath())) {
            log.warn("Reindex: doc {} has no stored file, can't re-process", docId);
            return;
        }

        try {
            byte[] bytes = fileStorageService.read(doc.getStoredPath());

            // Delete the existing raw embeddings for this doc before re-ingest.
            // The LangChain4j OpenAI-compatible embedding store doesn't expose a
            // typed delete API on metadata, so drop straight into pgvector.
            int deleted = jdbcTemplate.update(
                    "DELETE FROM embeddings WHERE (metadata->>'document_id')::uuid = ?",
                    docId);
            log.info("Reindex: deleted {} raw embeddings for doc {}", deleted, docId);

            // Re-run the full processing pipeline — anonymization now applies
            // inside processDocumentAsync, so the new embeddings will be clean.
            documentService.processDocumentAsync(docId, bytes);

        } catch (Exception e) {
            log.error("Reindex failed for doc {}: {}", docId, e.getMessage(), e);
        }
    }
}
