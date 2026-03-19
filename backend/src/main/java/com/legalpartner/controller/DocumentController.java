package com.legalpartner.controller;

import com.legalpartner.model.dto.DocumentDetail;
import com.legalpartner.model.dto.DocumentStats;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentMetadata upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String jurisdiction,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "false") boolean confidential,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String practiceArea,
            @RequestParam(required = false) String clientName,
            @RequestParam(required = false) String matterId,
            @RequestParam(required = false) String industry,
            Authentication auth
    ) {
        return documentService.ingestDocument(
                file, jurisdiction, year, confidential,
                documentType, practiceArea, clientName, matterId, industry,
                auth.getName()
        );
    }

    @GetMapping
    public Page<DocumentMetadata> list(Authentication auth, Pageable pageable) {
        String role = extractRole(auth);
        return documentService.listDocuments(role, pageable);
    }

    @GetMapping("/{id}")
    public DocumentDetail get(@PathVariable UUID id, Authentication auth) {
        return documentService.getDocument(id, extractRole(auth));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) {
        documentService.deleteDocument(id, auth.getName());
    }

    @GetMapping("/stats")
    public DocumentStats stats() {
        return documentService.getCorpusStats();
    }

    private String extractRole(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst().orElse("ROLE_ASSOCIATE");
    }
}
