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
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/editor")
@Slf4j
public class DocumentEditorController {

    private final DocumentMetadataRepository docRepo;
    private final FileStorageService fileStorage;

    /** Short-lived tokens: token → documentId. Expires after first use or on timeout. */
    private final Map<String, UUID> editorTokens = new ConcurrentHashMap<>();
    private final Map<String, Long> editorTokenExpiry = new ConcurrentHashMap<>();
    private static final long TOKEN_TTL_MS = 5 * 60 * 1000; // 5 minutes

    @Value("${legalpartner.onlyoffice.url:http://localhost:8443}")
    private String onlyofficeUrl;

    @Value("${legalpartner.cloud.backend-url:http://localhost:8080}")
    private String backendUrl;

    /** Internal URL that ONLYOFFICE container uses to reach the backend (Docker service name). */
    @Value("${legalpartner.onlyoffice.backend-url:http://backend:8080}")
    private String onlyofficeBackendUrl;

    public DocumentEditorController(DocumentMetadataRepository docRepo, FileStorageService fileStorage) {
        this.docRepo = docRepo;
        this.fileStorage = fileStorage;
    }

    /**
     * Get editor config for ONLYOFFICE — frontend uses this (authenticated via JWT).
     * Generates a one-time token for ONLYOFFICE to fetch the file and post callbacks.
     */
    @GetMapping("/{documentId}/config")
    public Map<String, Object> getEditorConfig(@PathVariable UUID documentId, Authentication auth) {
        DocumentMetadata doc = docRepo.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        if (doc.getStoredPath() == null || !fileStorage.exists(doc.getStoredPath())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original file not available for editing");
        }

        // Generate short-lived token for ONLYOFFICE server-to-server calls
        String token = UUID.randomUUID().toString();
        editorTokens.put(token, documentId);
        editorTokenExpiry.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        cleanExpiredTokens();

        // ONLYOFFICE server fetches files/callbacks server-side — use Docker-internal URL
        String fileUrl = onlyofficeBackendUrl + "/api/v1/editor/" + documentId + "/file?token=" + token;
        String callbackUrl = onlyofficeBackendUrl + "/api/v1/editor/" + documentId + "/callback?token=" + token;
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
     * Serve the actual file to ONLYOFFICE — secured by one-time token (no JWT).
     */
    @GetMapping("/{documentId}/file")
    public ResponseEntity<byte[]> getFile(@PathVariable UUID documentId, @RequestParam String token) {
        validateEditorToken(token, documentId);

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
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read file");
        }
    }

    /**
     * ONLYOFFICE callback — called when user saves the document. Secured by token.
     */
    @PostMapping("/{documentId}/callback")
    public Map<String, Integer> handleCallback(@PathVariable UUID documentId,
                                                @RequestParam String token,
                                                @RequestBody Map<String, Object> body) {
        validateEditorToken(token, documentId);

        int status = (int) body.getOrDefault("status", 0);
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

    private void validateEditorToken(String token, UUID documentId) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing editor token");
        }
        UUID allowed = editorTokens.get(token);
        Long expiry = editorTokenExpiry.get(token);
        if (allowed == null || expiry == null || System.currentTimeMillis() > expiry) {
            editorTokens.remove(token);
            editorTokenExpiry.remove(token);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired editor token");
        }
        if (!allowed.equals(documentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token does not match document");
        }
        // Don't remove on first use — ONLYOFFICE may call file + multiple callbacks with same token
    }

    private void cleanExpiredTokens() {
        long now = System.currentTimeMillis();
        editorTokenExpiry.entrySet().removeIf(e -> now > e.getValue());
        editorTokens.keySet().retainAll(editorTokenExpiry.keySet());
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
