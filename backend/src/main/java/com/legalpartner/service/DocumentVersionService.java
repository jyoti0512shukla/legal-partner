package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.DocumentVersion;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.model.enums.ContractStatus;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentVersionService {

    private final DocumentVersionRepository versionRepo;
    private final DocumentMetadataRepository documentRepo;
    private final ContractLifecycleService lifecycleService;
    private final AuditService auditService;

    @Value("${legalpartner.storage.path:/data/documents}")
    private String storagePath;

    @Transactional
    public DocumentVersion createVersion(UUID documentId, MultipartFile file,
                                          String source, String changeSummary, String username) {
        DocumentMetadata doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        lifecycleService.assertNotLocked(doc);

        int nextVersion = versionRepo.countByDocumentId(documentId) + 1;

        // Store file
        String ext = getExtension(file.getOriginalFilename());
        String versionPath = storagePath + "/" + documentId + "/v" + nextVersion + ext;
        try {
            Path path = Path.of(versionPath);
            Files.createDirectories(path.getParent());
            Files.write(path, file.getBytes());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store version file");
        }

        DocumentVersion version = DocumentVersion.builder()
                .document(doc)
                .versionNumber(nextVersion)
                .storedPath(versionPath)
                .fileSize(file.getSize())
                .source(source)
                .changeSummary(changeSummary)
                .createdBy(username)
                .build();
        DocumentVersion saved = versionRepo.save(version);

        // Update document pointer
        doc.setCurrentVersion(nextVersion);
        doc.setStoredPath(versionPath);
        if (file.getSize() > 0) doc.setFileSizeBytes(file.getSize());
        documentRepo.save(doc);

        // Auto-transition: counterparty redline on an APPROVED doc → NEGOTIATING
        if ("COUNTERPARTY".equals(source) && doc.getContractStatus() == ContractStatus.APPROVED) {
            lifecycleService.transitionStatus(documentId, ContractStatus.NEGOTIATING, username);
        }

        auditService.publish(AuditEvent.builder()
                .username(username)
                .action(AuditActionType.VERSION_UPLOADED)
                .documentId(documentId)
                .queryText("v" + nextVersion + " (" + source + ")")
                .success(true)
                .build());

        log.info("Version v{} created for doc {} by {} ({})", nextVersion, documentId, username, source);
        return saved;
    }

    @Transactional
    public DocumentVersion createVersionFromPath(UUID documentId, String filePath,
                                                  Long fileSize, String source,
                                                  String changeSummary, String username) {
        DocumentMetadata doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        int nextVersion = versionRepo.countByDocumentId(documentId) + 1;

        DocumentVersion version = DocumentVersion.builder()
                .document(doc)
                .versionNumber(nextVersion)
                .storedPath(filePath)
                .fileSize(fileSize)
                .source(source)
                .changeSummary(changeSummary)
                .createdBy(username)
                .build();
        DocumentVersion saved = versionRepo.save(version);

        doc.setCurrentVersion(nextVersion);
        documentRepo.save(doc);

        return saved;
    }

    public List<DocumentVersion> getVersions(UUID documentId) {
        return versionRepo.findByDocumentIdOrderByVersionNumberDesc(documentId);
    }

    public DocumentVersion getVersion(UUID documentId, int versionNumber) {
        return versionRepo.findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Version " + versionNumber + " not found for document " + documentId));
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
