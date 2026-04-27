package com.legalpartner.service;

import com.legalpartner.agent.MatterDocumentEvent;
import com.legalpartner.event.DocumentIndexedEvent;
import com.legalpartner.model.dto.DocumentDetail;
import com.legalpartner.model.dto.DocumentStats;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.enums.DocumentType;
import com.legalpartner.model.enums.PracticeArea;
import com.legalpartner.model.enums.ProcessingStatus;
import com.legalpartner.rag.LegalDocumentChunker;
import com.legalpartner.rag.LegalDocumentChunker.LegalChunk;
import com.legalpartner.repository.DocumentMetadataRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentMetadataRepository repository;
    private final LegalDocumentChunker chunker;
    private final EncryptionService encryptionService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ApplicationEventPublisher eventPublisher;
    private final FileStorageService fileStorageService;
    private final ContextualRetrievalService contextualRetrieval;
    private final AnonymizationService anonymizationService;
    private final DynamicEntityDenylistService dynamicDenylist;
    private final com.legalpartner.rag.Bm25SearchService bm25SearchService;

    private final Tika tika = new Tika();

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".txt", ".html", ".htm", ".rtf", ".odt", ".csv"
    );
    private static final Map<String, byte[]> MAGIC_BYTES = Map.of(
            "PDF",  new byte[]{0x25, 0x50, 0x44, 0x46},         // %PDF
            "DOCX", new byte[]{0x50, 0x4B, 0x03, 0x04},         // PK (ZIP)
            "DOC",  new byte[]{(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0}  // OLE2
    );

    private void validateUpload(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) throw new IllegalArgumentException("File name is required");

        // Path traversal protection
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Invalid file name");
        }

        // Extension whitelist
        String lower = name.toLowerCase();
        boolean validExt = ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!validExt) {
            throw new IllegalArgumentException("File type not allowed. Accepted: " + ALLOWED_EXTENSIONS);
        }

        // Magic byte validation for binary formats
        try {
            byte[] header = new byte[4];
            try (var is = file.getInputStream()) { is.read(header); }

            if (lower.endsWith(".pdf") && !startsWith(header, MAGIC_BYTES.get("PDF"))) {
                throw new IllegalArgumentException("File claims to be PDF but has invalid header");
            }
            if ((lower.endsWith(".docx") || lower.endsWith(".xlsx") || lower.endsWith(".odt"))
                    && !startsWith(header, MAGIC_BYTES.get("DOCX"))) {
                throw new IllegalArgumentException("File claims to be Office document but has invalid header");
            }
            if (lower.endsWith(".doc") && !startsWith(header, MAGIC_BYTES.get("DOC"))
                    && !startsWith(header, MAGIC_BYTES.get("DOCX"))) {
                throw new IllegalArgumentException("File claims to be DOC but has invalid header");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not validate magic bytes: {}", e.getMessage());
        }
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    public DocumentMetadata ingestDocument(
            MultipartFile file, String jurisdiction, Integer year,
            boolean confidential, String documentType, String practiceArea,
            String clientName, String matterId, String industry, String username
    ) {
        validateUpload(file);

        DocumentMetadata doc = DocumentMetadata.builder()
                .fileName(file.getOriginalFilename())
                .contentType(file.getContentType())
                .jurisdiction(jurisdiction)
                .year(year)
                .confidential(confidential)
                .documentType(documentType != null ? DocumentType.valueOf(documentType) : DocumentType.OTHER)
                .practiceArea(practiceArea != null ? PracticeArea.valueOf(practiceArea) : PracticeArea.OTHER)
                .clientName(clientName)
                .matterId(matterId)
                .industry(industry != null && !industry.isBlank() ? industry.toUpperCase() : null)
                .uploadedBy(username)
                .fileSizeBytes(file.getSize())
                .processingStatus(ProcessingStatus.PENDING)
                .build();
        doc = repository.save(doc);

        // Read bytes before async — MultipartFile is invalid after request completes
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            log.error("Failed to read file bytes: {}", e.getMessage());
            doc.setProcessingStatus(ProcessingStatus.FAILED);
            repository.save(doc);
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        // Store original file for ONLYOFFICE editing
        try {
            String storedPath = fileStorageService.store(doc.getId(), doc.getFileName(), fileBytes);
            doc.setStoredPath(storedPath);
            doc.setFileSize(file.getSize());
            repository.save(doc);
        } catch (Exception e) {
            log.warn("Failed to store original file (editor won't work): {}", e.getMessage());
        }

        processDocumentAsync(doc.getId(), fileBytes);
        return doc;
    }

    /**
     * Ingest a document from bytes (e.g. from cloud storage or EDGAR import).
     * source: "USER" (default), "EDGAR" (corpus seed), "CLOUD" (cloud import)
     */
    /**
     * Save an AI-generated draft as a Document. Skips Tika parsing (HTML is already text).
     * If matter is provided, links the document to it and triggers the matter agent.
     * Returns the saved DocumentMetadata immediately; indexing happens async.
     */
    public DocumentMetadata saveDraftAsDocument(
            String draftHtml, String fileName,
            com.legalpartner.model.entity.Matter matter,
            String jurisdiction, String username
    ) {
        DocumentMetadata doc = DocumentMetadata.builder()
                .fileName(fileName)
                .contentType("text/html")
                .jurisdiction(jurisdiction)
                .documentType(DocumentType.OTHER)
                .practiceArea(matter != null && matter.getPracticeArea() != null
                        ? matter.getPracticeArea() : PracticeArea.OTHER)
                .clientName(matter != null ? matter.getClientName() : null)
                .matter(matter)
                .matterId(matter != null ? matter.getId().toString() : null)
                .uploadedBy(username)
                .fileSizeBytes((long) draftHtml.length())
                .processingStatus(ProcessingStatus.PENDING)
                .source("DRAFTED")
                .build();
        doc = repository.save(doc);

        // Store HTML for the editor
        try {
            String storedPath = fileStorageService.store(doc.getId(), doc.getFileName(), draftHtml.getBytes());
            doc.setStoredPath(storedPath);
            doc.setFileSize((long) draftHtml.length());
            repository.save(doc);
        } catch (Exception e) {
            log.warn("Failed to store draft file: {}", e.getMessage());
        }

        // Index async (uses HTML as text — no Tika needed since draft is already prose)
        processDocumentAsync(doc.getId(), draftHtml.getBytes());
        log.info("Draft saved as document {} (matter={})", doc.getId(),
                matter != null ? matter.getMatterRef() : "none");
        return doc;
    }

    public DocumentMetadata ingestFromBytes(
            byte[] fileBytes, String fileName, String contentType,
            String jurisdiction, Integer year, boolean confidential,
            String documentType, String practiceArea, String clientName, String matterId,
            String industry, String username, String source
    ) {
        // documentType is required. Untagged precedents pollute RAG retrieval —
        // with no contract type, they match no scoping rule and shouldn't be ingested.
        // Exception: EDGAR corpus imports (handled separately) may skip this validation.
        boolean isSystemImport = source != null && !"USER".equalsIgnoreCase(source);
        if (!isSystemImport && (documentType == null || documentType.isBlank())) {
            throw new IllegalArgumentException(
                "Document type is required — pick NDA, MSA, SAAS, EMPLOYMENT, etc. " +
                "Untagged precedents can't be scoped for RAG retrieval.");
        }

        DocumentMetadata doc = DocumentMetadata.builder()
                .fileName(fileName)
                .contentType(contentType)
                .jurisdiction(jurisdiction)
                .year(year)
                .confidential(confidential)
                .documentType(documentType != null ? DocumentType.valueOf(documentType) : DocumentType.OTHER)
                .practiceArea(practiceArea != null ? PracticeArea.valueOf(practiceArea) : PracticeArea.OTHER)
                .clientName(clientName)
                .matterId(matterId)
                .industry(industry != null && !industry.isBlank() ? industry.toUpperCase() : null)
                .uploadedBy(username)
                .fileSizeBytes((long) fileBytes.length)
                .processingStatus(ProcessingStatus.PENDING)
                .source(source != null ? source : "USER")
                .build();
        doc = repository.save(doc);
        processDocumentAsync(doc.getId(), fileBytes);
        return doc;
    }

    /**
     * Short document-level summary used by Contextual Retrieval to situate each
     * chunk during embedding. We don't call the LLM here — the first paragraph
     * of the doc + its metadata tags give enough context for the chunk-level LLM
     * call to produce a useful prefix.
     */
    private String buildDocumentSummary(DocumentMetadata doc, String fullText) {
        StringBuilder sb = new StringBuilder();
        sb.append(doc.getFileName() != null ? doc.getFileName() : "Document");
        if (doc.getDocumentType() != null) sb.append(" | type=").append(doc.getDocumentType().name());
        if (doc.getJurisdiction() != null) sb.append(" | jurisdiction=").append(doc.getJurisdiction());
        if (doc.getIndustry() != null) sb.append(" | industry=").append(doc.getIndustry());
        if (fullText != null && !fullText.isBlank()) {
            sb.append("\n\n");
            sb.append(fullText.length() > 1500 ? fullText.substring(0, 1500) + "..." : fullText);
        }
        return sb.toString();
    }

    @Async
    public void processDocumentAsync(UUID docId, byte[] fileBytes) {
        DocumentMetadata doc = repository.findById(docId).orElseThrow();
        try {
            doc.setProcessingStatus(ProcessingStatus.PROCESSING);
            repository.save(doc);

            String rawText;
            try (InputStream is = new java.io.ByteArrayInputStream(fileBytes)) {
                rawText = tika.parseToString(is);
            }

            // Client-confidentiality layer: anonymize the document before chunking.
            // The ANONYMIZED text is what gets chunked, embedded, and surfaced to
            // the model as firm-wide RAG precedent. The raw→synthetic map is
            // stored encrypted on the DocumentMetadata row; only the originating
            // matter can decrypt it to show the lawyer the real names. This means
            // Client A's Acme/Mahindra/Ontario never reach Client B's draft —
            // they're physically absent from the embeddings.
            AnonymizationService.AnonymizationResult anon = anonymizationService.anonymize(rawText);
            String text = anon.anonymizedText();
            if (!anon.entityMap().isEmpty()) {
                try {
                    // Store the map encrypted so only the originating matter can read it
                    String mapJson = new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(anon.entityMap());
                    doc.setAnonymizationMapJson(encryptionService.encrypt(mapJson));
                    doc.setAnonymized(true);
                } catch (Exception mapSaveErr) {
                    log.warn("Failed to persist anonymization map for {}: {}",
                            doc.getId(), mapSaveErr.getMessage());
                }
            }

            Map<String, String> docMeta = new HashMap<>();
            docMeta.put("file_name", doc.getFileName());
            docMeta.put("document_id", doc.getId().toString());
            if (doc.getSource() != null) docMeta.put("source", doc.getSource());
            if (doc.getJurisdiction() != null) docMeta.put("jurisdiction", doc.getJurisdiction());
            if (doc.getYear() != null) docMeta.put("year", doc.getYear().toString());
            if (doc.getDocumentType() != null) docMeta.put("document_type", doc.getDocumentType().name());
            if (doc.getPracticeArea() != null) docMeta.put("practice_area", doc.getPracticeArea().name());
            if (doc.getIndustry() != null) docMeta.put("industry", doc.getIndustry());
            if (doc.getClientName() != null) docMeta.put("client_name", doc.getClientName());
            // is_anonymized flag — chunks from pre-anonymization-era docs are excluded
            // from drafting retrieval by default. Run the re-index endpoint to process them.
            docMeta.put("is_anonymized", Boolean.toString(doc.isAnonymized()));
            // matter_id enables matter-scoped RAG retrieval — only pull precedents from
            // the same matter when drafting within that matter.
            if (doc.getMatter() != null) docMeta.put("matter_id", doc.getMatter().getId().toString());
            else if (doc.getMatterId() != null && !doc.getMatterId().isBlank()) docMeta.put("matter_id", doc.getMatterId());

            List<LegalChunk> chunks = chunker.chunk(text, docMeta);

            List<TextSegment> segments = new ArrayList<>();
            List<Embedding> embeddings = new ArrayList<>();

            // Document-level summary to prepend to each chunk during embedding.
            // Contextual Retrieval (Anthropic technique) — improves retrieval recall
            // by ~49-67% vs plain chunk embedding. Pay one LLM call per chunk at
            // ingest; free at query time.
            String docSummary = buildDocumentSummary(doc, text);

            for (LegalChunk chunk : chunks) {
                // Generate a contextualized version for embedding, but keep the raw
                // chunk text for storage/display (we decrypt and show the raw text to
                // the user; the context prefix is only for retrieval quality).
                String rawChunkText = chunk.text();
                String contextualText = contextualRetrieval.contextualise(docSummary, rawChunkText);
                // all-minilm caps at ~256 tokens; truncate the contextualized text to fit
                String embedText = contextualText.length() > 500
                        ? contextualText.substring(0, 500)
                        : contextualText;

                String encrypted = encryptionService.encrypt(rawChunkText);
                TextSegment segment = TextSegment.from(encrypted, Metadata.from(chunk.metadata()));
                segments.add(segment);

                Embedding embedding = embeddingModel.embed(embedText).content();
                embeddings.add(embedding);
            }

            embeddingStore.addAll(embeddings, segments);

            // Index chunks for BM25 keyword search (hybrid retrieval)
            for (int i = 0; i < chunks.size(); i++) {
                LegalChunk chunk = chunks.get(i);
                UUID chunkId = UUID.nameUUIDFromBytes((doc.getId() + ":" + i).getBytes());
                try {
                    bm25SearchService.upsertChunk(chunkId, doc.getId(), chunk.text());
                } catch (Exception e) {
                    log.debug("BM25 upsert failed for chunk {}: {}", i, e.getMessage());
                }
            }
            log.info("Document {} BM25 indexed: {} chunks", doc.getFileName(), chunks.size());

            doc.setSegmentCount(chunks.size());
            doc.setProcessingStatus(ProcessingStatus.INDEXED);
            repository.save(doc);

            log.info("Document {} indexed: {} segments", doc.getFileName(), chunks.size());
            eventPublisher.publishEvent(new DocumentIndexedEvent(doc.getId(), doc.getUploadedBy(), doc.getFileName()));

            // Refresh the dynamic entity denylist — this doc's real entities
            // are now stored in its anonymization map and should be blocked
            // from appearing in other matters' drafts.
            if (doc.isAnonymized()) {
                dynamicDenylist.refreshNow();
            }

            // Trigger Deal Intelligence Agent if document is linked to a matter
            if (doc.getMatter() != null) {
                eventPublisher.publishEvent(new MatterDocumentEvent(
                        doc.getMatter().getId(), doc.getId(), "LINKED", doc.getUploadedBy()));
            }
        } catch (Exception e) {
            log.error("Failed to process document {}: {}", doc.getFileName(), e.getMessage(), e);
            doc.setProcessingStatus(ProcessingStatus.FAILED);
            repository.save(doc);
        }
    }

    public Page<DocumentMetadata> listDocuments(String userRole, Pageable pageable) {
        if (userRole.contains("ASSOCIATE")) {
            return repository.findBySourceNotAndConfidentialFalse("EDGAR", pageable);
        }
        return repository.findBySourceNot("EDGAR", pageable);
    }

    public DocumentDetail getDocument(UUID id, String userRole) {
        DocumentMetadata doc = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + id));
        if (userRole.contains("ASSOCIATE") && doc.isConfidential()) {
            throw new SecurityException("Associates cannot access confidential documents");
        }
        return new DocumentDetail(doc, Map.of());
    }

    public void deleteDocument(UUID id, String username) {
        repository.findById(id).orElseThrow(() -> new NoSuchElementException("Document not found: " + id));
        repository.deleteById(id);
        log.info("Document {} deleted by {}", id, username);
    }

    public DocumentStats getCorpusStats() {
        long totalDocs = repository.count();
        Long totalSegments = repository.sumSegmentCount();

        Map<String, Integer> byJurisdiction = repository.countByJurisdiction().stream()
                .collect(Collectors.toMap(r -> r[0].toString(), r -> ((Long) r[1]).intValue()));

        Map<String, Integer> byPractice = repository.countByPracticeArea().stream()
                .collect(Collectors.toMap(r -> r[0].toString(), r -> ((Long) r[1]).intValue()));

        return new DocumentStats(totalDocs, totalSegments != null ? totalSegments : 0,
                Map.of(), byJurisdiction, byPractice);
    }
}
