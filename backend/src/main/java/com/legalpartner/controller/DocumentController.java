package com.legalpartner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.DocumentDetail;
import com.legalpartner.model.dto.DocumentStats;
import com.legalpartner.model.entity.*;
import com.legalpartner.model.enums.ContractStatus;
import com.legalpartner.repository.ContractDeadlineRepository;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.DocumentNoteRepository;
import com.legalpartner.repository.MatterRepository;
import com.legalpartner.service.ContractLifecycleService;
import com.legalpartner.service.DeadlineService;
import com.legalpartner.service.DocumentService;
import com.legalpartner.service.DocumentVersionService;
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
    private final ContractLifecycleService lifecycleService;
    private final DocumentVersionService versionService;
    private final DeadlineService deadlineService;
    private final DocumentNoteRepository noteRepository;
    private final ObjectMapper objectMapper;

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
    public Page<DocumentMetadata> list(
            Authentication auth,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String contractStatus,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String matterId,
            @RequestParam(required = false) String expiryBefore,
            @RequestParam(required = false) String expiryAfter,
            Pageable pageable) {
        String role = extractRole(auth);
        boolean hasFilters = search != null || contractStatus != null || documentType != null
                || matterId != null || expiryBefore != null || expiryAfter != null;
        if (hasFilters) {
            boolean confidentialFilter = role.contains("ASSOCIATE");
            String s = (search != null && !search.isBlank()) ? search.trim() : null;
            String cs = (contractStatus != null && !contractStatus.isBlank()) ? contractStatus : null;
            String dt = (documentType != null && !documentType.isBlank()) ? documentType : null;
            String mi = (matterId != null && !matterId.isBlank()) ? matterId : null;
            String eb = (expiryBefore != null && !expiryBefore.isBlank()) ? expiryBefore : null;
            String ea = (expiryAfter != null && !expiryAfter.isBlank()) ? expiryAfter : null;
            return documentRepository.searchAndFilter(confidentialFilter, s, cs, dt, mi, eb, ea, pageable);
        }
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

    // ── Contract Lifecycle ──

    @PostMapping("/{id}/lifecycle/initialize")
    public DocumentMetadata initializeLifecycle(@PathVariable UUID id, Authentication auth) {
        return lifecycleService.initializeLifecycle(id, auth.getName());
    }

    @PostMapping("/{id}/lifecycle/transition")
    public DocumentMetadata transitionStatus(
            @PathVariable UUID id,
            @RequestParam ContractStatus status,
            Authentication auth) {
        return lifecycleService.transitionStatus(id, status, auth.getName());
    }

    @GetMapping("/{id}/lifecycle/allowed-transitions")
    public Map<String, Object> getAllowedTransitions(@PathVariable UUID id) {
        DocumentMetadata doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        return Map.of(
                "currentStatus", doc.getContractStatus() != null ? doc.getContractStatus().name() : "NONE",
                "allowedTransitions", lifecycleService.getAllowedNextStatuses(doc.getContractStatus())
        );
    }

    // ── Finalization ──

    @GetMapping("/{id}/finalize/prefill")
    public Map<String, Object> getFinalizationPrefill(@PathVariable UUID id) {
        DocumentMetadata doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        List<String> keyPoints = new ArrayList<>();
        if (doc.getPartyA() != null) keyPoints.add("Party A: " + doc.getPartyA());
        if (doc.getPartyB() != null) keyPoints.add("Party B: " + doc.getPartyB());
        if (doc.getExpiryDate() != null) keyPoints.add("Expires: " + doc.getExpiryDate());
        if (doc.getContractValue() != null) keyPoints.add("Value: " + doc.getContractValue());
        if (doc.getLiabilityCap() != null) keyPoints.add("Liability cap: " + doc.getLiabilityCap());
        if (doc.getNoticePeriodDays() != null) keyPoints.add("Notice period: " + doc.getNoticePeriodDays() + " days");
        if (doc.getGoverningLawJurisdiction() != null) keyPoints.add("Governing law: " + doc.getGoverningLawJurisdiction());
        return Map.of(
                "suggestedBrief", doc.getSummaryText() != null ? doc.getSummaryText() : "",
                "suggestedKeyPoints", keyPoints
        );
    }

    @PostMapping("/{id}/finalize")
    public DocumentMetadata finalizeDocument(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String userBrief = (String) request.get("userBrief");
        @SuppressWarnings("unchecked")
        List<String> keyPoints = (List<String>) request.getOrDefault("keyPoints", List.of());
        String keyPointsJson;
        try { keyPointsJson = objectMapper.writeValueAsString(keyPoints); }
        catch (Exception e) { keyPointsJson = "[]"; }
        return lifecycleService.finalize(id, userBrief, keyPointsJson, auth.getName());
    }

    @PostMapping("/{id}/execute")
    public DocumentMetadata markExecuted(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> request,
            Authentication auth) {
        if (request != null && request.containsKey("userBrief")) {
            String userBrief = (String) request.get("userBrief");
            @SuppressWarnings("unchecked")
            List<String> keyPoints = (List<String>) request.getOrDefault("keyPoints", List.of());
            String keyPointsJson;
            try { keyPointsJson = objectMapper.writeValueAsString(keyPoints); }
            catch (Exception e) { keyPointsJson = "[]"; }
            lifecycleService.finalize(id, userBrief, keyPointsJson, auth.getName());
        }
        return lifecycleService.markExecuted(id, auth.getName());
    }

    // ── Versions ──

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentVersion uploadVersion(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "UPLOAD") String source,
            @RequestParam(required = false) String changeSummary,
            Authentication auth) {
        return versionService.createVersion(id, file, source, changeSummary, auth.getName());
    }

    @GetMapping("/{id}/versions")
    public List<DocumentVersion> getVersions(@PathVariable UUID id) {
        return versionService.getVersions(id);
    }

    // ── Deadlines ──

    @GetMapping("/{id}/deadlines")
    public List<ContractDeadline> getDocumentDeadlines(@PathVariable UUID id) {
        return deadlineService.getDeadlinesForDocument(id);
    }

    @GetMapping("/deadlines/upcoming")
    public List<Map<String, Object>> getUpcomingDeadlines(
            @RequestParam(defaultValue = "10") int limit) {
        return deadlineService.getUpcomingDeadlines(limit);
    }

    @PostMapping("/deadlines/{deadlineId}/action")
    public ContractDeadline actionDeadline(@PathVariable UUID deadlineId, Authentication auth) {
        return deadlineService.actionDeadline(deadlineId, auth.getName());
    }

    @GetMapping("/deadlines/config")
    public List<DeadlineAlertConfig> getAlertConfig() {
        return deadlineService.getAlertConfig();
    }

    @PostMapping("/deadlines/config")
    @ResponseStatus(HttpStatus.CREATED)
    public DeadlineAlertConfig addAlertConfig(@RequestBody Map<String, Object> body) {
        int days = (Integer) body.get("alertWindowDays");
        String channel = (String) body.getOrDefault("notifyChannel", "EMAIL");
        return deadlineService.addAlertConfig(days, channel);
    }

    @PutMapping("/deadlines/config/{configId}")
    public DeadlineAlertConfig updateAlertConfig(@PathVariable UUID configId, @RequestBody Map<String, Object> body) {
        return deadlineService.updateAlertConfig(configId, body);
    }

    @DeleteMapping("/deadlines/config/{configId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAlertConfig(@PathVariable UUID configId) {
        deadlineService.removeAlertConfig(configId);
    }

    @GetMapping("/active-count")
    public Map<String, Long> getActiveContractCount() {
        return Map.of("count", documentRepository.countActiveContracts());
    }

    // ── Notes ──

    @GetMapping("/{id}/notes")
    public List<Map<String, Object>> getNotes(@PathVariable UUID id) {
        return noteRepository.findByDocumentIdOrderByCreatedAtDesc(id).stream()
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n.getId());
                    m.put("content", n.getContent());
                    m.put("createdBy", n.getCreatedBy());
                    m.put("createdAt", n.getCreatedAt().toString());
                    return m;
                }).toList();
    }

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addNote(@PathVariable UUID id, @RequestBody Map<String, String> body, Authentication auth) {
        String content = body.get("content");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Note content is required");
        DocumentMetadata doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        DocumentNote note = DocumentNote.builder()
                .document(doc).content(content.trim()).createdBy(auth.getName()).build();
        DocumentNote saved = noteRepository.save(note);
        return Map.of("id", saved.getId(), "content", saved.getContent(),
                "createdBy", saved.getCreatedBy(), "createdAt", saved.getCreatedAt().toString());
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable UUID noteId, Authentication auth) {
        DocumentNote note = noteRepository.findById(noteId)
                .orElseThrow(() -> new IllegalArgumentException("Note not found"));
        if (!note.getCreatedBy().equals(auth.getName())) {
            throw new SecurityException("You can only delete your own notes");
        }
        noteRepository.deleteById(noteId);
    }

    // ── Metadata Edit ──

    @PatchMapping("/{id}/metadata")
    public DocumentMetadata updateMetadata(@PathVariable UUID id, @RequestBody Map<String, Object> updates, Authentication auth) {
        DocumentMetadata doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        if (doc.isLocked()) throw new IllegalStateException("Cannot edit locked document");
        if (updates.containsKey("partyA")) doc.setPartyA((String) updates.get("partyA"));
        if (updates.containsKey("partyB")) doc.setPartyB((String) updates.get("partyB"));
        if (updates.containsKey("clientName")) doc.setClientName((String) updates.get("clientName"));
        if (updates.containsKey("jurisdiction")) doc.setJurisdiction((String) updates.get("jurisdiction"));
        if (updates.containsKey("contractValue")) doc.setContractValue((String) updates.get("contractValue"));
        if (updates.containsKey("userBrief")) doc.setUserBrief((String) updates.get("userBrief"));
        return documentRepository.save(doc);
    }

    // ── Helpers ──

    private Map<String, Object> docToGroupedItem(DocumentMetadata d) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", d.getId().toString());
        item.put("fileName", d.getFileName());
        item.put("contractType", d.getDocumentType() != null ? d.getDocumentType().name() : null);
        item.put("source", d.getSource());
        item.put("uploadDate", d.getUploadDate() != null ? d.getUploadDate().toString() : null);
        item.put("status", d.getProcessingStatus() != null ? d.getProcessingStatus().name() : null);
        item.put("contractStatus", d.getContractStatus() != null ? d.getContractStatus().name() : null);
        item.put("locked", d.isLocked());
        item.put("userBrief", d.getUserBrief());
        item.put("currentVersion", d.getCurrentVersion());
        return item;
    }

    private String extractRole(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst().orElse("ROLE_ASSOCIATE");
    }
}
