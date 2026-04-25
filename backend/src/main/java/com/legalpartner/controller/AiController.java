package com.legalpartner.controller;

import com.legalpartner.config.LegalSystemConfig;
import org.springframework.beans.factory.annotation.Value;
import com.legalpartner.model.dto.*;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.Matter;
import com.legalpartner.model.entity.User;
import com.legalpartner.repository.MatterRepository;
import com.legalpartner.model.enums.DocumentType;
import com.legalpartner.model.enums.PracticeArea;
import com.legalpartner.model.enums.ProcessingStatus;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.service.AiService;
import com.legalpartner.service.DocumentService;
import com.legalpartner.service.DraftService;
import com.legalpartner.service.FileStorageService;
import com.legalpartner.service.MatterAccessService;
import com.legalpartner.service.TemplateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final DraftService draftService;
    private final com.legalpartner.service.DraftIntakeValidator draftIntakeValidator;
    private final com.legalpartner.config.ContractTypeRegistry contractTypeRegistry;
    private final com.legalpartner.config.ClauseTypeRegistry clauseRegistry;
    private final TemplateService templateService;
    private final LegalSystemConfig legalSystemConfig;
    private final DocumentService documentService;
    private final MatterAccessService matterAccessService;
    private final MatterRepository matterRepository;
    private final DocumentMetadataRepository documentRepository;
    private final FileStorageService fileStorageService;

    @GetMapping("/templates")
    public List<TemplateInfo> listTemplates() {
        return templateService.listTemplates();
    }

    @Value("${legalpartner.organization.name:}")
    private String organizationName;

    @GetMapping("/legal-system")
    public java.util.Map<String, String> legalSystem() {
        var map = new java.util.LinkedHashMap<String, String>();
        map.put("system", legalSystemConfig.getLegalSystem());
        map.put("country", legalSystemConfig.country());
        map.put("contractAct", legalSystemConfig.contractAct());
        map.put("arbitrationAct", legalSystemConfig.arbitrationAct());
        if (organizationName != null && !organizationName.isBlank()) {
            map.put("organizationName", organizationName);
        }
        return map;
    }

    @PostMapping("/draft")
    public DraftResponse draft(@Valid @RequestBody DraftRequest request, Authentication auth) {
        return draftService.generateDraft(request, auth.getName());
    }

    @PostMapping(value = "/draft/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDraft(@Valid @RequestBody DraftRequest request, Authentication auth) {
        return draftService.streamDraft(request, auth.getName());
    }

    // ── Async drafts: submit + poll + list ─────────────────────────────────

    /**
     * Kick off a draft that runs in the background. Returns immediately with
     * a draft id the UI can poll. Survives tab close, logout, and device switch.
     */
    /**
     * Validate draft request before generation. Extracts DealSpec from brief,
     * checks required/recommended fields, returns what's missing.
     * Frontend calls this first — if ready=true, proceed to /draft/async.
     * If ready=false, show missing fields for user to fill in or skip.
     */
    @PostMapping("/draft/validate")
    public java.util.Map<String, Object> validateDraft(
            @Valid @RequestBody DraftRequest request, Authentication auth) {
        var result = draftIntakeValidator.validate(request);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ready", result.ready());
        out.put("missingRequired", result.missingRequired().stream().map(f ->
                java.util.Map.of("field", f.fieldPath(), "label", f.label(), "required", f.required())
        ).toList());
        out.put("missingRecommended", result.missingRecommended().stream().map(f ->
                java.util.Map.of("field", f.fieldPath(), "label", f.label(), "required", f.required())
        ).toList());
        // Return extracted DealSpec so frontend can show what was understood
        if (result.dealSpec() != null) {
            var ds = result.dealSpec();
            java.util.Map<String, Object> extracted = new java.util.LinkedHashMap<>();
            if (ds.getPartyA() != null) {
                extracted.put("partyA", ds.getPartyA().getName());
                extracted.put("partyAAddress", ds.getPartyA().getAddress());
                extracted.put("partyARole", ds.getPartyA().getRole());
            }
            if (ds.getPartyB() != null) {
                extracted.put("partyB", ds.getPartyB().getName());
                extracted.put("partyBAddress", ds.getPartyB().getAddress());
                extracted.put("partyBRole", ds.getPartyB().getRole());
            }
            if (ds.getLegal() != null) {
                extracted.put("jurisdiction", ds.getLegal().getJurisdiction());
                extracted.put("court", ds.getLegal().getCourt());
            }
            if (ds.getLicense() != null) {
                extracted.put("licenseType", ds.getLicense().getType());
                extracted.put("users", ds.getLicense().getUsers());
                extracted.put("locations", ds.getLicense().getLocations());
            }
            if (ds.getFees() != null) {
                extracted.put("licenseFee", ds.getFees().getLicenseFee());
                extracted.put("maintenanceFee", ds.getFees().getMaintenanceFee());
            }
            if (ds.getSupport() != null) {
                extracted.put("slaResponseHours", ds.getSupport().getSlaResponseHours());
                extracted.put("coverage", ds.getSupport().getCoverage());
            }
            if (ds.getSecurity() != null) {
                extracted.put("escrow", ds.getSecurity().getEscrow());
            }
            out.put("extracted", extracted);
        }

        // Return planned clauses from contract_types.yml
        var config = contractTypeRegistry.get(request.getTemplateId());
        if (config != null) {
            out.put("contractDisplayName", config.displayName());
            out.put("partyRoles", config.partyRoles());
            // Build clause list with display names from clauseRegistry
            var defaultKeys = new java.util.HashSet<>(config.defaultSections());
            out.put("plannedClauses", config.defaultSections().stream().map(key -> {
                var clauseConfig = clauseRegistry.get(key);
                return java.util.Map.of(
                        "key", key,
                        "title", clauseConfig != null ? clauseConfig.title() : key,
                        "enabled", true
                );
            }).toList());
            // All available clause types not in the default list — for "Add clause"
            out.put("availableClauses", clauseRegistry.allKeys().stream()
                    .filter(key -> !defaultKeys.contains(key))
                    .map(key -> {
                        var clauseConfig = clauseRegistry.get(key);
                        return java.util.Map.of(
                                "key", key,
                                "title", clauseConfig != null ? clauseConfig.title() : key
                        );
                    }).toList());
        }
        return out;
    }

    @PostMapping("/draft/async")
    public java.util.Map<String, Object> submitAsyncDraft(
            @Valid @RequestBody DraftRequest request,
            Authentication auth) {

        Matter matter = null;
        if (request.getMatterId() != null && !request.getMatterId().isBlank()) {
            UUID matterUuid;
            try { matterUuid = UUID.fromString(request.getMatterId()); }
            catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid matterId"); }
            matter = matterRepository.findById(matterUuid)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Matter not found"));
            User user = matterAccessService.resolveUser(auth);
            matterAccessService.requireMembership(matterUuid, user.getId(), user.getRole());
        }

        String fileName = buildAsyncDraftFileName(request, matter);

        DocumentMetadata doc = DocumentMetadata.builder()
                .fileName(fileName)
                .contentType("text/html")
                .jurisdiction(request.getJurisdiction())
                .documentType(DocumentType.OTHER)
                .practiceArea(matter != null && matter.getPracticeArea() != null
                        ? matter.getPracticeArea() : PracticeArea.OTHER)
                .clientName(matter != null ? matter.getClientName() : null)
                .matter(matter)
                .matterId(matter != null ? matter.getId().toString() : null)
                .uploadedBy(auth.getName())
                .processingStatus(ProcessingStatus.PENDING)
                .source("DRAFT_ASYNC")
                .build();
        doc = documentRepository.save(doc);

        // @Async — returns immediately; Spring runs generateDraftAsync on the async pool.
        draftService.generateDraftAsync(doc.getId(), request, auth.getName());

        return java.util.Map.of("id", doc.getId().toString(), "fileName", doc.getFileName());
    }

    /**
     * Poll for a specific async draft's current state. Returns status + progress
     * counters + the latest-persisted partial HTML so the UI can show live updates.
     */
    @GetMapping("/draft/async/{id}")
    public java.util.Map<String, Object> pollAsyncDraft(@PathVariable UUID id, Authentication auth) {
        DocumentMetadata doc = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
        if (!auth.getName().equals(doc.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your draft");
        }

        String html = "";
        if (doc.getStoredPath() != null && fileStorageService.exists(doc.getStoredPath())) {
            try { html = new String(fileStorageService.read(doc.getStoredPath())); }
            catch (java.io.IOException ignored) {}
        }

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", doc.getId().toString());
        out.put("fileName", doc.getFileName());
        out.put("status", doc.getProcessingStatus().name());
        out.put("totalClauses", doc.getTotalClauses());
        out.put("completedClauses", doc.getCompletedClauses());
        out.put("currentClauseLabel", doc.getCurrentClauseLabel());
        out.put("lastProgressAt", doc.getLastProgressAt() != null ? doc.getLastProgressAt().toString() : null);
        out.put("errorMessage", doc.getErrorMessage());
        out.put("draftHtml", html);
        out.put("createdAt", doc.getUploadDate() != null ? doc.getUploadDate().toString() : null);
        // Draft parameters HTML (stored separately from the contract body)
        String parametersHtml = draftService.readParametersHtml(doc);
        if (parametersHtml != null) {
            out.put("draftParametersHtml", parametersHtml);
        }
        // Duration in seconds (only when complete)
        if (doc.getProcessingStatus() == com.legalpartner.model.enums.ProcessingStatus.INDEXED
                && doc.getUploadDate() != null && doc.getLastProgressAt() != null) {
            long seconds = java.time.Duration.between(doc.getUploadDate(), doc.getLastProgressAt()).getSeconds();
            out.put("durationSeconds", seconds);
        }
        return out;
    }

    /**
     * Download the DOCX version of a completed draft.
     * The DOCX is generated on draft completion and stored alongside the HTML.
     */
    @GetMapping("/draft/async/{id}/docx")
    public org.springframework.http.ResponseEntity<byte[]> downloadDocx(@PathVariable UUID id, Authentication auth) {
        DocumentMetadata doc = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
        if (!auth.getName().equals(doc.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your draft");
        }
        java.nio.file.Path docxPath = fileStorageService.resolve(id + ".docx");
        if (!java.nio.file.Files.exists(docxPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DOCX not available yet — draft may still be generating");
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(docxPath);
            String filename = doc.getFileName() != null
                    ? doc.getFileName().replace(".html", ".docx")
                    : "draft-contract.docx";
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    .body(bytes);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read DOCX file");
        }
    }

    /**
     * List this user's recent async drafts — the "Recent drafts" strip on the
     * Draft page. Returns most recent 20, newest first. No HTML to keep it light.
     */
    @GetMapping("/drafts")
    public List<java.util.Map<String, Object>> listAsyncDrafts(Authentication auth) {
        List<DocumentMetadata> docs = documentRepository
                .findTop20ByUploadedByAndSourceOrderByUploadDateDesc(auth.getName(), "DRAFT_ASYNC");
        return docs.stream().map(d -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", d.getId().toString());
            m.put("fileName", d.getFileName());
            m.put("status", d.getProcessingStatus().name());
            m.put("totalClauses", d.getTotalClauses());
            m.put("completedClauses", d.getCompletedClauses());
            m.put("currentClauseLabel", d.getCurrentClauseLabel());
            m.put("errorMessage", d.getErrorMessage());
            m.put("createdAt", d.getUploadDate() != null ? d.getUploadDate().toString() : null);
            m.put("lastProgressAt", d.getLastProgressAt() != null ? d.getLastProgressAt().toString() : null);
            if (d.getProcessingStatus() == com.legalpartner.model.enums.ProcessingStatus.INDEXED
                    && d.getUploadDate() != null && d.getLastProgressAt() != null) {
                m.put("durationSeconds", java.time.Duration.between(d.getUploadDate(), d.getLastProgressAt()).getSeconds());
            }
            return m;
        }).collect(java.util.stream.Collectors.toList());
    }

    private String buildAsyncDraftFileName(DraftRequest request, Matter matter) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (matter != null && matter.getName() != null) parts.add(matter.getName());
        else if (request.getTemplateId() != null) parts.add(request.getTemplateId().toUpperCase() + " Draft");
        else parts.add("Draft");
        if (request.getPartyA() != null && !request.getPartyA().isBlank()) parts.add(request.getPartyA());
        if (request.getPartyB() != null && !request.getPartyB().isBlank()) parts.add(request.getPartyB());
        parts.add(java.time.LocalDate.now().toString());
        String name = parts.stream()
                .map(p -> p.replaceAll("[^a-zA-Z0-9\\s-]", "").trim().replaceAll("\\s+", "-"))
                .filter(p -> !p.isEmpty())
                .collect(java.util.stream.Collectors.joining("-"));
        return name + ".html";
    }

    /**
     * Save a generated draft as a Document. If matterId is provided, the
     * document is attached to the matter and the user must have access.
     */
    @PostMapping("/draft/save")
    public java.util.Map<String, Object> saveDraft(
            @Valid @RequestBody SaveDraftRequest request,
            Authentication auth) {
        User user = matterAccessService.resolveUser(auth);

        Matter matter = null;
        if (request.getMatterId() != null && !request.getMatterId().isBlank()) {
            UUID matterUuid;
            try {
                matterUuid = UUID.fromString(request.getMatterId());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid matterId");
            }
            matter = matterRepository.findById(matterUuid)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Matter not found"));
            // Access check — throws 403 if user is not a member (ADMIN bypasses)
            matterAccessService.requireMembership(matterUuid, user.getId(), user.getRole());
        }

        // Auto-generate file name
        String fileName = buildDraftFileName(request, matter);

        DocumentMetadata doc = documentService.saveDraftAsDocument(
                request.getDraftHtml(),
                fileName,
                matter,
                request.getJurisdiction(),
                auth.getName()
        );

        return java.util.Map.of(
                "id", doc.getId().toString(),
                "fileName", doc.getFileName(),
                "matterId", matter != null ? matter.getId().toString() : "",
                "status", doc.getProcessingStatus().name()
        );
    }

    /**
     * Build a sensible file name:
     *   With matter:    "MatterName-PartyA-PartyB-2026-04-10.html"
     *   Without matter: "ContractType-PartyA-PartyB-2026-04-10.html"
     */
    private String buildDraftFileName(SaveDraftRequest request, Matter matter) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (matter != null && matter.getName() != null) parts.add(matter.getName());
        else if (request.getContractTypeName() != null) parts.add(request.getContractTypeName());
        else parts.add("Draft");

        if (request.getPartyA() != null && !request.getPartyA().isBlank()) parts.add(request.getPartyA());
        if (request.getPartyB() != null && !request.getPartyB().isBlank()) parts.add(request.getPartyB());
        parts.add(java.time.LocalDate.now().toString());

        // Sanitize: keep alphanumerics, spaces, hyphens
        String name = parts.stream()
                .map(p -> p.replaceAll("[^a-zA-Z0-9\\s-]", "").trim().replaceAll("\\s+", "-"))
                .filter(p -> !p.isEmpty())
                .collect(java.util.stream.Collectors.joining("-"));
        return name + ".html";
    }

    @PostMapping("/query")
    public QueryResult query(@Valid @RequestBody QueryRequest request, Authentication auth) {
        return aiService.query(request, auth.getName());
    }

    @PostMapping("/compare")
    public CompareResult compare(@Valid @RequestBody CompareRequest request, Authentication auth) {
        return aiService.compare(request, auth.getName());
    }

    @PostMapping("/risk-assessment/{docId}")
    public java.util.Map<String, Object> riskAssessment(
            @PathVariable UUID docId,
            @RequestParam(value = "regenerate", defaultValue = "false") boolean regenerate,
            Authentication auth) {
        DocumentMetadata doc = documentRepository.findById(docId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        // Check if we can return cached result before calling service
        boolean wasCached = !regenerate && doc.getRiskAssessmentJson() != null && !doc.getRiskAssessmentJson().isBlank();

        RiskAssessmentResult result = aiService.assessRisk(docId, auth.getName(), regenerate);

        // Re-read the doc to get the latest generatedAt after a fresh run
        if (!wasCached) {
            doc = documentRepository.findById(docId).orElse(doc);
        }

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("overallRisk", result.overallRisk());
        out.put("categories", result.categories());
        out.put("riskScore", result.riskScore());
        out.put("clauseResults", result.clauseResults());
        out.put("missingClauses", result.missingClauses());
        out.put("keyFindings", result.keyFindings());
        out.put("cached", wasCached);
        out.put("generatedAt", doc.getRiskAssessmentAt() != null ? doc.getRiskAssessmentAt().toString() : null);
        return out;
    }

    @PostMapping("/risk-drilldown/{docId}")
    public RiskDrilldownResult riskDrilldown(
            @PathVariable UUID docId,
            @RequestBody RiskDrilldownRequest request,
            Authentication auth) {
        return aiService.riskDrilldown(docId, request, auth.getName());
    }

    @PostMapping("/extract/{docId}")
    public ExtractionResult extract(@PathVariable UUID docId, Authentication auth) {
        return aiService.extractKeyTerms(docId, auth.getName());
    }

    /**
     * AI summary of a document — returns cached result if present, otherwise
     * generates on demand. Pass ?regenerate=true to force a fresh run.
     */
    @PostMapping("/summarize/{docId}")
    public java.util.Map<String, Object> summarize(
            @PathVariable UUID docId,
            @RequestParam(value = "regenerate", defaultValue = "false") boolean regenerate,
            Authentication auth) {
        return aiService.summarizeDocument(docId, auth.getName(), regenerate);
    }

    /** Contract-scoped Q&A — answer grounded in the selected document only. */
    @PostMapping("/ask/{docId}")
    public java.util.Map<String, Object> askContract(
            @PathVariable UUID docId,
            @RequestBody java.util.Map<String, String> body,
            Authentication auth) {
        return aiService.askContract(docId, body != null ? body.get("question") : null, auth.getName());
    }

    @PostMapping("/refine-clause")
    public RefineClauseResponse refineClause(@Valid @RequestBody RefineClauseRequest request, Authentication auth) {
        return aiService.refineClause(request, auth.getName());
    }
}
