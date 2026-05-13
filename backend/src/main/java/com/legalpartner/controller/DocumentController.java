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
import com.legalpartner.audit.AuditEvent;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.service.AuditService;
import com.legalpartner.service.ContractLifecycleService;
import com.legalpartner.service.DeadlineService;
import com.legalpartner.service.DocumentService;
import com.legalpartner.service.DocumentVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final AuditService auditService;
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

    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER')")
    @PostMapping("/{id}/lifecycle/initialize")
    public DocumentMetadata initializeLifecycle(@PathVariable UUID id, Authentication auth) {
        return lifecycleService.initializeLifecycle(id, auth.getName());
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER')")
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

    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER')")
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

    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER')")
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

    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER')")
    @PostMapping("/deadlines/config")
    @ResponseStatus(HttpStatus.CREATED)
    public DeadlineAlertConfig addAlertConfig(@RequestBody Map<String, Object> body, Authentication auth) {
        int days = (Integer) body.get("alertWindowDays");
        String channel = (String) body.getOrDefault("notifyChannel", "EMAIL");
        DeadlineAlertConfig saved = deadlineService.addAlertConfig(days, channel);
        auditService.publish(AuditEvent.builder().username(auth.getName())
                .action(AuditActionType.DEADLINE_CREATED).queryText("Alert config added: " + days + "d " + channel).success(true).build());
        return saved;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER')")
    @PutMapping("/deadlines/config/{configId}")
    public DeadlineAlertConfig updateAlertConfig(@PathVariable UUID configId, @RequestBody Map<String, Object> body, Authentication auth) {
        DeadlineAlertConfig saved = deadlineService.updateAlertConfig(configId, body);
        auditService.publish(AuditEvent.builder().username(auth.getName())
                .action(AuditActionType.DEADLINE_CREATED).queryText("Alert config updated").success(true).build());
        return saved;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER')")
    @DeleteMapping("/deadlines/config/{configId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAlertConfig(@PathVariable UUID configId, Authentication auth) {
        deadlineService.removeAlertConfig(configId);
        auditService.publish(AuditEvent.builder().username(auth.getName())
                .action(AuditActionType.DEADLINE_CREATED).queryText("Alert config removed").success(true).build());
    }

    @GetMapping("/active-count")
    public Map<String, Long> getActiveContractCount() {
        return Map.of("count", documentRepository.countActiveContracts());
    }

    // ── Version Compare ──

    @GetMapping("/{id}/versions/{v1}/{v2}/compare")
    public Map<String, Object> compareVersions(@PathVariable UUID id, @PathVariable int v1, @PathVariable int v2) {
        var ver1 = versionService.getVersion(id, v1);
        var ver2 = versionService.getVersion(id, v2);
        String text1 = readVersionText(ver1.getStoredPath());
        String text2 = readVersionText(ver2.getStoredPath());
        return Map.of(
                "v1", Map.of("versionNumber", v1, "text", text1, "source", ver1.getSource(), "createdBy", ver1.getCreatedBy() != null ? ver1.getCreatedBy() : ""),
                "v2", Map.of("versionNumber", v2, "text", text2, "source", ver2.getSource(), "createdBy", ver2.getCreatedBy() != null ? ver2.getCreatedBy() : "")
        );
    }

    private String readVersionText(String path) {
        if (path == null) return "";
        try {
            java.nio.file.Path filePath = java.nio.file.Path.of(path);
            if (!java.nio.file.Files.exists(filePath)) {
                // Try with /data/documents prefix
                filePath = java.nio.file.Path.of("/data/documents", path);
            }
            if (!java.nio.file.Files.exists(filePath)) return "[File not found]";
            String content = java.nio.file.Files.readString(filePath);
            // Strip HTML tags for plain text comparison
            return content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            return "[Error reading file: " + e.getMessage() + "]";
        }
    }

    // ── Client Intelligence ──

    @GetMapping("/clients/distinct")
    public List<String> getDistinctClients() {
        return documentRepository.findDistinctClientNames();
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> listClients() {
        List<String> clientNames = documentRepository.findDistinctClientNames();
        return clientNames.stream().map(name -> {
            List<DocumentMetadata> docs = documentRepository.findByClientOrPartyFuzzy(name);
            long activeCount = docs.stream().filter(d -> d.getContractStatus() != null
                    && (d.getContractStatus().name().equals("ACTIVE") || d.getContractStatus().name().equals("EXPIRING"))).count();
            String latestDate = docs.isEmpty() ? null : docs.get(0).getUploadDate().toString();
            java.util.Set<String> types = new java.util.LinkedHashSet<>();
            java.util.Set<String> matterNames = new java.util.LinkedHashSet<>();
            for (DocumentMetadata d : docs) {
                if (d.getDocumentType() != null) types.add(d.getDocumentType().name());
                if (d.getMatter() != null) matterNames.add(d.getMatter().getName());
            }
            Map<String, Object> client = new LinkedHashMap<>();
            client.put("clientName", name);
            client.put("contractCount", docs.size());
            client.put("activeCount", activeCount);
            client.put("latestActivity", latestDate);
            client.put("contractTypes", types);
            client.put("matters", matterNames);
            return client;
        }).toList();
    }

    @GetMapping("/clients/{name}/profile")
    public Map<String, Object> getClientProfile(@PathVariable String name) {
        List<DocumentMetadata> docs = documentRepository.findByClientOrPartyFuzzy(name);
        if (docs.isEmpty()) throw new IllegalArgumentException("No contracts found for client: " + name);

        // Key terms history — track how terms evolved
        Map<String, List<String>> termsHistory = new LinkedHashMap<>();
        for (DocumentMetadata d : docs) {
            String date = d.getUploadDate() != null ? d.getUploadDate().toString().substring(0, 10) : "?";
            if (d.getLiabilityCap() != null) termsHistory.computeIfAbsent("liabilityCap", k -> new ArrayList<>()).add(d.getLiabilityCap() + " (" + date + ")");
            if (d.getContractValue() != null) termsHistory.computeIfAbsent("contractValue", k -> new ArrayList<>()).add(d.getContractValue() + " (" + date + ")");
            if (d.getGoverningLawJurisdiction() != null) termsHistory.computeIfAbsent("jurisdiction", k -> new ArrayList<>()).add(d.getGoverningLawJurisdiction() + " (" + date + ")");
            if (d.getNoticePeriodDays() != null) termsHistory.computeIfAbsent("noticePeriod", k -> new ArrayList<>()).add(d.getNoticePeriodDays() + " days (" + date + ")");
        }

        // Matters
        java.util.Set<Map<String, Object>> matters = new java.util.LinkedHashSet<>();
        for (DocumentMetadata d : docs) {
            if (d.getMatter() != null) {
                matters.add(Map.of("id", d.getMatter().getId(), "name", d.getMatter().getName()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientName", name);
        result.put("contractCount", docs.size());
        result.put("contracts", docs);
        result.put("matters", matters);
        result.put("keyTermsHistory", termsHistory);
        // Latest terms from most recent doc
        if (!docs.isEmpty()) {
            DocumentMetadata latest = docs.get(0);
            Map<String, Object> lt = new LinkedHashMap<>();
            lt.put("contractType", latest.getDocumentType() != null ? latest.getDocumentType().name() : null);
            lt.put("contractValue", latest.getContractValue());
            lt.put("liabilityCap", latest.getLiabilityCap());
            lt.put("jurisdiction", latest.getGoverningLawJurisdiction());
            lt.put("noticePeriod", latest.getNoticePeriodDays());
            result.put("latestTerms", lt);
        }
        return result;
    }

    @GetMapping("/clients/lookup")
    public Map<String, Object> lookupClient(@RequestParam String name) {
        if (name == null || name.isBlank()) return Map.of("matched", false);
        List<DocumentMetadata> docs = documentRepository.findByClientOrPartyFuzzy(name.trim());
        if (docs.isEmpty()) return Map.of("matched", false);

        DocumentMetadata latest = docs.get(0);
        Map<String, Object> latestTerms = new LinkedHashMap<>();
        latestTerms.put("lastContractType", latest.getDocumentType() != null ? latest.getDocumentType().name() : null);
        latestTerms.put("lastContractValue", latest.getContractValue());
        latestTerms.put("lastLiabilityCap", latest.getLiabilityCap());
        latestTerms.put("lastJurisdiction", latest.getGoverningLawJurisdiction());
        latestTerms.put("lastNoticePeriod", latest.getNoticePeriodDays());
        latestTerms.put("lastExpiryDate", latest.getExpiryDate() != null ? latest.getExpiryDate().toString() : null);
        latestTerms.put("lastStatus", latest.getContractStatus() != null ? latest.getContractStatus().name() : null);

        String matchedClient = latest.getClientName() != null ? latest.getClientName()
                : latest.getPartyB() != null ? latest.getPartyB() : latest.getPartyA();

        List<Map<String, Object>> contracts = docs.stream().map(d -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", d.getId());
            c.put("fileName", d.getFileName());
            c.put("documentType", d.getDocumentType() != null ? d.getDocumentType().name() : null);
            c.put("contractStatus", d.getContractStatus() != null ? d.getContractStatus().name() : null);
            c.put("contractValue", d.getContractValue());
            c.put("expiryDate", d.getExpiryDate() != null ? d.getExpiryDate().toString() : null);
            c.put("liabilityCap", d.getLiabilityCap());
            c.put("jurisdiction", d.getGoverningLawJurisdiction());
            c.put("uploadDate", d.getUploadDate() != null ? d.getUploadDate().toString() : null);
            return c;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matched", true);
        result.put("clientName", matchedClient);
        result.put("contractCount", docs.size());
        result.put("contracts", contracts);
        result.put("latestTerms", latestTerms);
        return result;
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
        auditService.publish(AuditEvent.builder().username(auth.getName())
                .action(AuditActionType.NOTE_ADDED).documentId(id)
                .queryText("Note added").success(true).build());
        return Map.of("id", saved.getId(), "content", saved.getContent(),
                "createdBy", saved.getCreatedBy(), "createdAt", saved.getCreatedAt().toString());
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable UUID noteId, Authentication auth) {
        DocumentNote note = noteRepository.findById(noteId)
                .orElseThrow(() -> new IllegalArgumentException("Note not found"));
        String role = extractRole(auth);
        if (!note.getCreatedBy().equals(auth.getName()) && !role.contains("ADMIN")) {
            throw new SecurityException("You can only delete your own notes");
        }
        noteRepository.deleteById(noteId);
        auditService.publish(AuditEvent.builder().username(auth.getName())
                .action(AuditActionType.NOTE_DELETED).documentId(note.getDocument().getId())
                .queryText("Note deleted").success(true).build());
    }

    // ── Metadata Edit ──

    @PreAuthorize("hasAnyRole('ADMIN', 'PARTNER')")
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
        DocumentMetadata saved = documentRepository.save(doc);
        auditService.publish(AuditEvent.builder().username(auth.getName())
                .action(AuditActionType.METADATA_EDITED).documentId(id)
                .queryText("Fields updated: " + String.join(", ", updates.keySet())).success(true).build());
        return saved;
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
