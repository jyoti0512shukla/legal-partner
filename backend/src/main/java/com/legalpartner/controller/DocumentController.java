package com.legalpartner.controller;

import com.legalpartner.model.dto.DocumentDetail;
import com.legalpartner.model.dto.DocumentStats;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.Matter;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.MatterRepository;
import com.legalpartner.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentMetadataRepository documentRepository;
    private final MatterRepository matterRepository;

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

    /**
     * Documents grouped by matter, plus unassigned and drafts buckets.
     * Used by the Contract Review page's grouped selector.
     */
    @GetMapping("/grouped")
    public Map<String, Object> grouped(Authentication auth) {
        String role = extractRole(auth);
        // Get all non-EDGAR documents (same filter as list())
        List<DocumentMetadata> allDocs;
        if (role.contains("ASSOCIATE")) {
            allDocs = documentRepository.findBySourceNotAndConfidentialFalse("EDGAR",
                    org.springframework.data.domain.PageRequest.of(0, 500)).getContent();
        } else {
            allDocs = documentRepository.findBySourceNot("EDGAR",
                    org.springframework.data.domain.PageRequest.of(0, 500)).getContent();
        }

        List<Map<String, Object>> drafts = new ArrayList<>();
        List<Map<String, Object>> unassigned = new ArrayList<>();
        Map<UUID, List<Map<String, Object>>> byMatter = new LinkedHashMap<>();

        for (DocumentMetadata d : allDocs) {
            Map<String, Object> item = docToGroupedItem(d);
            if ("DRAFT_ASYNC".equals(d.getSource())) {
                drafts.add(item);
            } else if (d.getMatter() == null) {
                unassigned.add(item);
            } else {
                byMatter.computeIfAbsent(d.getMatter().getId(), k -> new ArrayList<>()).add(item);
            }
        }

        // Build matters list with names
        List<Map<String, Object>> matters = new ArrayList<>();
        for (Map.Entry<UUID, List<Map<String, Object>>> entry : byMatter.entrySet()) {
            UUID matterId = entry.getKey();
            Matter matter = matterRepository.findById(matterId).orElse(null);
            Map<String, Object> matterGroup = new LinkedHashMap<>();
            matterGroup.put("matterId", matterId.toString());
            matterGroup.put("matterName", matter != null ? matter.getName() : "Unknown Matter");
            matterGroup.put("documents", entry.getValue());
            matters.add(matterGroup);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matters", matters);
        result.put("unassigned", unassigned);
        result.put("drafts", drafts);
        return result;
    }

    private Map<String, Object> docToGroupedItem(DocumentMetadata d) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", d.getId().toString());
        item.put("fileName", d.getFileName());
        item.put("contractType", d.getDocumentType() != null ? d.getDocumentType().name() : null);
        item.put("source", d.getSource());
        item.put("uploadDate", d.getUploadDate() != null ? d.getUploadDate().toString() : null);
        item.put("status", d.getProcessingStatus() != null ? d.getProcessingStatus().name() : null);
        return item;
    }

    private String extractRole(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst().orElse("ROLE_ASSOCIATE");
    }
}
