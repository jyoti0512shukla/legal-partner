package com.legalpartner.controller;

import com.legalpartner.config.WorkflowProperties;
import com.legalpartner.model.dto.CreateWorkflowRequest;
import com.legalpartner.model.dto.WorkflowDefinitionDto;
import com.legalpartner.model.dto.WorkflowRunDto;
import com.legalpartner.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
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

    @GetMapping("/definitions")
    public List<WorkflowDefinitionDto> listDefinitions() {
        checkEnabled();
        return workflowService.listDefinitions();
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

    @GetMapping("/runs")
    public List<WorkflowRunDto> listRuns(
            @RequestParam(defaultValue = "0") int page,
            Authentication auth) {
        checkEnabled();
        return workflowService.listRuns(auth.getName(), page);
    }

    @GetMapping("/runs/{id}")
    public WorkflowRunDto getRun(@PathVariable UUID id, Authentication auth) {
        checkEnabled();
        return workflowService.getRun(id, auth.getName());
    }

    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeWorkflow(
            @RequestParam UUID definitionId,
            @RequestParam UUID documentId,
            Authentication auth) {
        checkEnabled();
        return workflowService.executeWorkflow(definitionId, documentId, auth.getName());
    }
}
