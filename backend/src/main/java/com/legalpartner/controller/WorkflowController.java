package com.legalpartner.controller;

import com.legalpartner.config.WorkflowProperties;
import com.legalpartner.model.dto.*;
import com.legalpartner.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowProperties workflowProperties;

    private void checkEnabled() {
        if (!workflowProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflows feature is disabled");
        }
    }

    // ── Definitions ───────────────────────────────────────────────────────────

    @GetMapping("/definitions")
    public List<WorkflowDefinitionDto> listDefinitions(Authentication auth) {
        checkEnabled();
        return workflowService.listDefinitions(auth.getName());
    }

    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowDefinitionDto createDefinition(
            @Valid @RequestBody CreateWorkflowRequest req,
            Authentication auth) {
        checkEnabled();
        return workflowService.createDefinition(req, auth.getName());
    }

    @DeleteMapping("/definitions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDefinition(@PathVariable UUID id, Authentication auth) {
        checkEnabled();
        workflowService.deleteDefinition(id, auth.getName());
    }

    @PatchMapping("/definitions/{id}/promote")
    @PreAuthorize("hasAnyRole('PARTNER','ADMIN')")
    public WorkflowDefinitionDto promoteToTeam(@PathVariable UUID id, Authentication auth) {
        checkEnabled();
        return workflowService.promoteToTeam(id, auth.getName());
    }

    // ── Runs ──────────────────────────────────────────────────────────────────

    @GetMapping("/runs")
    public List<WorkflowRunDto> listRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String matterRef,
            Authentication auth) {
        checkEnabled();
        return workflowService.listRuns(auth.getName(), page, matterRef);
    }

    @GetMapping("/runs/{id}")
    public WorkflowRunDto getRun(@PathVariable UUID id, Authentication auth) {
        checkEnabled();
        return workflowService.getRun(id, auth.getName());
    }

    @GetMapping("/runs/{id}/export")
    public Map<String, Object> exportRun(@PathVariable UUID id, Authentication auth) {
        checkEnabled();
        return workflowService.exportRun(id, auth.getName());
    }

    @PatchMapping("/runs/{id}/matter")
    public WorkflowRunDto associateMatter(
            @PathVariable UUID id,
            @RequestParam String matterRef,
            Authentication auth) {
        checkEnabled();
        return workflowService.associateMatter(id, matterRef, auth.getName());
    }

    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeWorkflow(
            @RequestParam UUID definitionId,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) String partyA,
            @RequestParam(required = false) String partyB,
            @RequestParam(required = false) String jurisdiction,
            @RequestParam(required = false) String dealBrief,
            Authentication auth) {
        checkEnabled();
        Map<String, String> draftContext = new java.util.LinkedHashMap<>();
        if (partyA     != null) draftContext.put("partyA",      partyA);
        if (partyB     != null) draftContext.put("partyB",      partyB);
        if (jurisdiction != null) draftContext.put("jurisdiction", jurisdiction);
        if (dealBrief  != null) draftContext.put("dealBrief",   dealBrief);
        return workflowService.executeWorkflow(definitionId, documentId, auth.getName(),
                draftContext.isEmpty() ? null : draftContext);
    }

    // ── Analytics ─────────────────────────────────────────────────────────────

    @GetMapping("/analytics")
    public WorkflowAnalyticsDto analytics(Authentication auth) {
        checkEnabled();
        return workflowService.analytics(auth.getName());
    }
}
