package com.legalpartner.controller;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/v1/editor")
@Slf4j
public class DocumentEditorController {

    private final DocumentMetadataRepository docRepo;
    private final FileStorageService fileStorage;

    @Value("${legalpartner.onlyoffice.url:http://localhost:8443}")
    private String onlyofficeUrl;

    @Value("${legalpartner.onlyoffice.backend-url:http://backend:8080}")
    private String onlyofficeBackendUrl;

    public DocumentEditorController(DocumentMetadataRepository docRepo, FileStorageService fileStorage) {
        this.docRepo = docRepo;
        this.fileStorage = fileStorage;
    }

    /**
     * Get editor config — JWT-protected. Only authenticated users get the file URLs.
     * The document UUID in the URL acts as an unguessable access token.
     */
    @GetMapping("/{documentId}/config")
    public Map<String, Object> getEditorConfig(@PathVariable UUID documentId, Authentication auth) {
        DocumentMetadata doc = docRepo.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        if (doc.getStoredPath() == null || !fileStorage.exists(doc.getStoredPath())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original file not available for editing");
        }

        String fileUrl = onlyofficeBackendUrl + "/api/v1/editor/" + documentId + "/file";
        String callbackUrl = onlyofficeBackendUrl + "/api/v1/editor/" + documentId + "/callback";
        String fileType = getFileType(doc.getFileName());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("document", Map.of(
                "fileType", fileType,
                "key", documentId.toString() + "-" + System.currentTimeMillis(),
                "title", doc.getFileName(),
                "url", fileUrl
        ));
        config.put("editorConfig", Map.of(
                "callbackUrl", callbackUrl,
                "mode", "edit",
                "user", Map.of(
                        "id", auth.getName(),
                        "name", auth.getName()
                )
        ));
        config.put("documentType", fileType.equals("pdf") ? "pdf" : "word");
        config.put("onlyofficeUrl", onlyofficeUrl);

        return config;
    }

    /**
     * Serve file to ONLYOFFICE — permitAll (UUID is unguessable, only exposed via JWT-protected config).
     */
    @GetMapping("/{documentId}/file")
    public ResponseEntity<byte[]> getFile(@PathVariable UUID documentId) {
        DocumentMetadata doc = docRepo.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (doc.getStoredPath() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not stored");
        }

        try {
            byte[] content = fileStorage.read(doc.getStoredPath());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    doc.getContentType() != null ? doc.getContentType() : "application/octet-stream"));
            headers.setContentDisposition(ContentDisposition.inline().filename(doc.getFileName()).build());
            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to read file for doc {}: {}", documentId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read file");
        }
    }

    /**
     * ONLYOFFICE save callback — permitAll.
     */
    @PostMapping("/{documentId}/callback")
    public Map<String, Integer> handleCallback(@PathVariable UUID documentId,
                                                @RequestBody Map<String, Object> body) {
        int status = body.get("status") instanceof Number n ? n.intValue() : 0;
        log.info("ONLYOFFICE callback for doc {}: status={}", documentId, status);

        // Status 2 = document ready for saving, 6 = force save
        if (status == 2 || status == 6) {
            String downloadUrl = (String) body.get("url");
            if (downloadUrl != null) {
                try {
                    byte[] edited = new org.springframework.web.client.RestTemplate()
                            .getForObject(downloadUrl, byte[].class);
                    if (edited != null) {
                        DocumentMetadata doc = docRepo.findById(documentId).orElse(null);
                        if (doc != null && doc.getStoredPath() != null) {
                            java.nio.file.Files.write(java.nio.file.Paths.get(doc.getStoredPath()), edited);
                            doc.setFileSize((long) edited.length);
                            docRepo.save(doc);
                            log.info("Saved edited document {} ({} bytes)", documentId, edited.length);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to save edited document {}: {}", documentId, e.getMessage());
                }
            }
        }

        return Map.of("error", 0);
    }

    private String getFileType(String fileName) {
        if (fileName == null) return "docx";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".doc")) return "doc";
        if (lower.endsWith(".xlsx")) return "xlsx";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        return "docx";
    }
}
