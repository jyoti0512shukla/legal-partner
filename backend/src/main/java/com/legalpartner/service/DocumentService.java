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

    private final Tika tika = new Tika();

    public DocumentMetadata ingestDocument(
            MultipartFile file, String jurisdiction, Integer year,
            boolean confidential, String documentType, String practiceArea,
            String clientName, String matterId, String industry, String username
    ) {
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

    @Async
    public void processDocumentAsync(UUID docId, byte[] fileBytes) {
        DocumentMetadata doc = repository.findById(docId).orElseThrow();
        try {
            doc.setProcessingStatus(ProcessingStatus.PROCESSING);
            repository.save(doc);

            String text;
            try (InputStream is = new java.io.ByteArrayInputStream(fileBytes)) {
                text = tika.parseToString(is);
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
            // matter_id enables matter-scoped RAG retrieval — only pull precedents from
            // the same matter when drafting within that matter.
            if (doc.getMatter() != null) docMeta.put("matter_id", doc.getMatter().getId().toString());
            else if (doc.getMatterId() != null && !doc.getMatterId().isBlank()) docMeta.put("matter_id", doc.getMatterId());

            List<LegalChunk> chunks = chunker.chunk(text, docMeta);

            List<TextSegment> segments = new ArrayList<>();
            List<Embedding> embeddings = new ArrayList<>();

            for (LegalChunk chunk : chunks) {
                String chunkText = chunk.text();
                if (chunkText.length() > 400) chunkText = chunkText.substring(0, 400); // all-minilm 256 token limit
                String encrypted = encryptionService.encrypt(chunk.text());
                TextSegment segment = TextSegment.from(encrypted, Metadata.from(chunk.metadata()));
                segments.add(segment);

                Embedding embedding = embeddingModel.embed(chunkText).content();
                embeddings.add(embedding);
            }

            embeddingStore.addAll(embeddings, segments);

            doc.setSegmentCount(chunks.size());
            doc.setProcessingStatus(ProcessingStatus.INDEXED);
            repository.save(doc);

            log.info("Document {} indexed: {} segments", doc.getFileName(), chunks.size());
            eventPublisher.publishEvent(new DocumentIndexedEvent(doc.getId(), doc.getUploadedBy(), doc.getFileName()));

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
