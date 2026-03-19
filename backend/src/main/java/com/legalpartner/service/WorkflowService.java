package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.WorkflowProperties;
import com.legalpartner.model.dto.*;
import com.legalpartner.model.entity.WorkflowDefinition;
import com.legalpartner.model.entity.WorkflowRun;
import com.legalpartner.model.enums.WorkflowStepType;
import com.legalpartner.model.enums.WorkflowStatus;
import com.legalpartner.repository.WorkflowDefinitionRepository;
import com.legalpartner.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Profile("!test")
public class WorkflowService implements ApplicationRunner {

    private final WorkflowDefinitionRepository definitionRepo;
    private final WorkflowRunRepository runRepo;
    private final WorkflowProperties workflowProperties;
    private final WorkflowExecutor executor;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!workflowProperties.isEnabled()) return;
        seedPredefinedWorkflows();
    }

    private void seedPredefinedWorkflows() {
        seedIfAbsent("Due Diligence",
                "Full contract analysis: extract terms, assess risk, and audit clauses",
                List.of(
                        new WorkflowStepConfig(WorkflowStepType.EXTRACT_KEY_TERMS, "Extract Key Terms"),
                        new WorkflowStepConfig(WorkflowStepType.RISK_ASSESSMENT, "Risk Assessment"),
                        new WorkflowStepConfig(WorkflowStepType.CLAUSE_CHECKLIST, "Clause Checklist")
                ));

        seedIfAbsent("Contract Review",
                "Rapid review: audit standard clauses and identify risks",
                List.of(
                        new WorkflowStepConfig(WorkflowStepType.CLAUSE_CHECKLIST, "Clause Checklist"),
                        new WorkflowStepConfig(WorkflowStepType.RISK_ASSESSMENT, "Risk Assessment")
                ));

        seedIfAbsent("Key Terms Only",
                "Extract structured key terms and data points from the document",
                List.of(
                        new WorkflowStepConfig(WorkflowStepType.EXTRACT_KEY_TERMS, "Extract Key Terms")
                ));
    }

    private void seedIfAbsent(String name, String description, List<WorkflowStepConfig> steps) {
        if (definitionRepo.existsByNameAndCreatedBy(name, null)) return;
        try {
            WorkflowDefinition def = WorkflowDefinition.builder()
                    .name(name)
                    .description(description)
                    .predefined(true)
                    .steps(objectMapper.writeValueAsString(steps))
                    .build();
            definitionRepo.save(def);
            log.info("Seeded predefined workflow: {}", name);
        } catch (Exception e) {
            log.error("Failed to seed workflow '{}': {}", name, e.getMessage());
        }
    }

    public List<WorkflowDefinitionDto> listDefinitions() {
        return definitionRepo.findAllByOrderByCreatedAtAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    public WorkflowDefinitionDto createDefinition(CreateWorkflowRequest req, String username) {
        try {
            WorkflowDefinition def = WorkflowDefinition.builder()
                    .name(req.getName())
                    .description(req.getDescription())
                    .predefined(false)
                    .steps(objectMapper.writeValueAsString(req.getSteps()))
                    .createdBy(username)
                    .build();
            return toDto(definitionRepo.save(def));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save workflow");
        }
    }

    public void deleteDefinition(UUID id, String username) {
        WorkflowDefinition def = definitionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
        if (def.isPredefined()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete predefined workflows");
        }
        if (!def.getCreatedBy().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your workflow");
        }
        definitionRepo.delete(def);
    }

    public List<WorkflowRunDto> listRuns(String username, int page) {
        return runRepo.findByUsernameOrderByStartedAtDesc(username, PageRequest.of(page, 20))
                .stream()
                .map(this::toRunDto)
                .toList();
    }

    public WorkflowRunDto getRun(UUID id, String username) {
        WorkflowRun run = runRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        if (!run.getUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your run");
        }
        return toRunDto(run);
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter executeWorkflow(
            UUID definitionId, UUID documentId, String username) {

        WorkflowDefinition def = definitionRepo.findById(definitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));

        List<WorkflowStepConfig> steps;
        try {
            steps = objectMapper.readValue(def.getSteps(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid workflow definition");
        }

        WorkflowRun run = WorkflowRun.builder()
                .definition(def)
                .documentId(documentId)
                .username(username)
                .status(WorkflowStatus.PENDING)
                .build();
        WorkflowRun saved = runRepo.save(run);

        return executor.execute(saved, steps, def.getName());
    }

    private WorkflowDefinitionDto toDto(WorkflowDefinition def) {
        List<WorkflowStepConfig> steps = List.of();
        try {
            steps = objectMapper.readValue(def.getSteps(), new TypeReference<>() {});
        } catch (Exception ignored) {}
        return WorkflowDefinitionDto.builder()
                .id(def.getId())
                .name(def.getName())
                .description(def.getDescription())
                .predefined(def.isPredefined())
                .steps(steps)
                .createdBy(def.getCreatedBy())
                .createdAt(def.getCreatedAt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private WorkflowRunDto toRunDto(WorkflowRun run) {
        Map<String, Object> results = Map.of();
        try {
            if (run.getResults() != null) {
                results = objectMapper.readValue(run.getResults(), new TypeReference<>() {});
            }
        } catch (Exception ignored) {}

        List<WorkflowStepConfig> steps = List.of();
        try {
            steps = objectMapper.readValue(run.getDefinition().getSteps(), new TypeReference<>() {});
        } catch (Exception ignored) {}

        return WorkflowRunDto.builder()
                .id(run.getId())
                .workflowDefinitionId(run.getDefinition().getId())
                .workflowName(run.getDefinition().getName())
                .documentId(run.getDocumentId())
                .username(run.getUsername())
                .status(run.getStatus())
                .currentStep(run.getCurrentStep())
                .totalSteps(steps.size())
                .results(results)
                .errorMessage(run.getErrorMessage())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .build();
    }
}
