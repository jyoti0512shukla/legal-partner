package com.legalpartner.controller;

import com.legalpartner.config.LegalSystemConfig;
import com.legalpartner.model.dto.*;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.Matter;
import com.legalpartner.model.entity.User;
import com.legalpartner.repository.MatterRepository;
import com.legalpartner.service.AiService;
import com.legalpartner.service.DocumentService;
import com.legalpartner.service.DraftService;
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
    private final TemplateService templateService;
    private final LegalSystemConfig legalSystemConfig;
    private final DocumentService documentService;
    private final MatterAccessService matterAccessService;
    private final MatterRepository matterRepository;

    @GetMapping("/templates")
    public List<TemplateInfo> listTemplates() {
        return templateService.listTemplates();
    }

    @GetMapping("/legal-system")
    public java.util.Map<String, String> legalSystem() {
        return java.util.Map.of(
            "system", legalSystemConfig.getLegalSystem(),
            "country", legalSystemConfig.country(),
            "contractAct", legalSystemConfig.contractAct(),
            "arbitrationAct", legalSystemConfig.arbitrationAct()
        );
    }

    @PostMapping("/draft")
    public DraftResponse draft(@Valid @RequestBody DraftRequest request, Authentication auth) {
        return draftService.generateDraft(request, auth.getName());
    }

    @PostMapping(value = "/draft/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDraft(@Valid @RequestBody DraftRequest request, Authentication auth) {
        return draftService.streamDraft(request, auth.getName());
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
    public RiskAssessmentResult riskAssessment(@PathVariable UUID docId, Authentication auth) {
        return aiService.assessRisk(docId, auth.getName());
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

    @PostMapping("/refine-clause")
    public RefineClauseResponse refineClause(@Valid @RequestBody RefineClauseRequest request, Authentication auth) {
        return aiService.refineClause(request, auth.getName());
    }
}
