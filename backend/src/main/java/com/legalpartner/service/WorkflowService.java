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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

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
                        step(WorkflowStepType.EXTRACT_KEY_TERMS, "Extract Key Terms", null, 1),
                        step(WorkflowStepType.RISK_ASSESSMENT,   "Risk Assessment (RAG)", null, 2),
                        step(WorkflowStepType.CLAUSE_CHECKLIST,  "Clause Checklist", null, 2),
                        step(WorkflowStepType.GENERATE_SUMMARY,  "Executive Summary", null, 1)
                ));

        seedIfAbsent("Contract Review",
                "Rapid review: audit clauses, corpus-benchmarked risk, and firm-grounded redlines",
                List.of(
                        step(WorkflowStepType.CLAUSE_CHECKLIST,   "Clause Checklist", null, 2),
                        step(WorkflowStepType.RISK_ASSESSMENT,    "Risk Assessment (RAG)", null, 2),
                        step(WorkflowStepType.REDLINE_SUGGESTIONS,"Redline Suggestions (Firm Clauses)", null, 2)
                ));

        seedIfAbsent("Key Terms Only",
                "Extract structured key terms and data points from the document",
                List.of(
                        step(WorkflowStepType.EXTRACT_KEY_TERMS, "Extract Key Terms", null, 1)
                ));

        seedIfAbsent("High-Risk Deep Dive",
                "Full analysis with conditional redlines — redlines generated only when risk is HIGH",
                List.of(
                        step(WorkflowStepType.RISK_ASSESSMENT,    "Risk Assessment (RAG)", null, 2),
                        step(WorkflowStepType.CLAUSE_CHECKLIST,   "Clause Checklist", null, 2),
                        step(WorkflowStepType.REDLINE_SUGGESTIONS,"Redline Suggestions (Firm Clauses)",
                                new WorkflowCondition("RISK_ASSESSMENT.overallRisk", "eq", "HIGH"), 2),
                        step(WorkflowStepType.GENERATE_SUMMARY,   "Executive Summary", null, 1)
                ));

        // ── New: Playbook Review — clause library grounded, full iterative loop ──
        seedIfAbsent("Playbook Review",
                "Checklist → corpus-benchmarked risk → firm-grounded redlines, each step self-refines up to 2 passes",
                List.of(
                        step(WorkflowStepType.CLAUSE_CHECKLIST,   "Clause Checklist (Refined)", null, 2),
                        step(WorkflowStepType.RISK_ASSESSMENT,    "Risk Benchmark vs Corpus", null, 2),
                        step(WorkflowStepType.REDLINE_SUGGESTIONS,"Redlines from Firm Playbook", null, 2),
                        step(WorkflowStepType.GENERATE_SUMMARY,   "Executive Memo", null, 1)
                ));

        // ── New: Draft & Assess Loop — draft a clause, assess, refine ────────────
        seedIfAbsent("Draft & Assess Loop",
                "Draft a clause using corpus + firm library → assess risk → refine redlines → summary. Each step iterates until quality passes.",
                List.of(
                        stepWithParams(WorkflowStepType.DRAFT_CLAUSE, "Draft Liability Clause",  null, 2, Map.of("clauseType", "LIABILITY")),
                        step(WorkflowStepType.RISK_ASSESSMENT,        "Assess Drafted Clause",    null, 2),
                        step(WorkflowStepType.REDLINE_SUGGESTIONS,    "Refine with Firm Playbook",
                                new WorkflowCondition("RISK_ASSESSMENT.overallRisk", "in", "HIGH,MEDIUM"), 2),
                        step(WorkflowStepType.GENERATE_SUMMARY,       "Draft Summary", null, 1)
                ));
    }

    private static WorkflowStepConfig step(WorkflowStepType type, String label,
                                           WorkflowCondition condition, int maxIterations) {
        return WorkflowStepConfig.builder()
                .type(type).label(label).condition(condition)
                .retryCount(0).maxIterations(maxIterations).build();
    }

    private static WorkflowStepConfig stepWithParams(WorkflowStepType type, String label,
                                                     WorkflowCondition condition, int maxIterations,
                                                     Map<String, String> params) {
        return WorkflowStepConfig.builder()
                .type(type).label(label).condition(condition)
                .retryCount(0).maxIterations(maxIterations).params(params).build();
    }

    private void seedIfAbsent(String name, String description, List<WorkflowStepConfig> steps) {
        if (definitionRepo.existsByNameAndCreatedByIsNull(name)) return;
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

    public List<WorkflowDefinitionDto> listDefinitions(String username) {
        return definitionRepo.findVisibleToUser(username)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public WorkflowDefinitionDto createDefinition(CreateWorkflowRequest req, String username) {
        try {
            List<WorkflowConnector> connectors = req.getConnectors() != null ? req.getConnectors() : List.of();
            WorkflowDefinition def = WorkflowDefinition.builder()
                    .name(req.getName())
                    .description(req.getDescription())
                    .predefined(false)
                    .team(req.isTeam())
                    .autoTrigger(req.isAutoTrigger())
                    .steps(objectMapper.writeValueAsString(req.getSteps()))
                    .connectors(objectMapper.writeValueAsString(connectors))
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

    public WorkflowDefinitionDto promoteToTeam(UUID id, String username) {
        WorkflowDefinition def = definitionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
        if (def.isPredefined()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Predefined workflows are already shared");
        }
        def.setTeam(!def.isTeam());  // toggle
        return toDto(definitionRepo.save(def));
    }

    public List<WorkflowRunDto> listRuns(String username, int page, String matterRef) {
        Page<WorkflowRun> result = (matterRef != null && !matterRef.isBlank())
                ? runRepo.findByUsernameAndMatterRefIgnoreCaseOrderByStartedAtDesc(username, matterRef.trim(), PageRequest.of(page, 20))
                : runRepo.findByUsernameOrderByStartedAtDesc(username, PageRequest.of(page, 20));
        return result.stream().map(this::toRunDto).toList();
    }

    public WorkflowRunDto getRun(UUID id, String username) {
        WorkflowRun run = runRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        if (!run.getUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your run");
        }
        return toRunDto(run);
    }

    public Map<String, Object> exportRun(UUID id, String username) {
        WorkflowRun run = runRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        if (!run.getUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your run");
        }
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("runId", run.getId().toString());
        export.put("workflowName", run.getDefinition().getName());
        export.put("documentId", run.getDocumentId().toString());
        export.put("status", run.getStatus().name());
        export.put("matterRef", run.getMatterRef());
        export.put("startedAt", run.getStartedAt().toString());
        export.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
        if (run.getResults() != null) {
            try {
                export.put("results", objectMapper.readValue(run.getResults(), new TypeReference<Map<String, Object>>() {}));
            } catch (Exception ignored) {}
        }
        return export;
    }

    public WorkflowRunDto associateMatter(UUID id, String matterRef, String username) {
        WorkflowRun run = runRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        if (!run.getUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your run");
        }
        run.setMatterRef(matterRef);
        return toRunDto(runRepo.save(run));
    }

    public SseEmitter executeWorkflow(UUID definitionId, UUID documentId, String username) {
        WorkflowDefinition def = definitionRepo.findById(definitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
        List<WorkflowStepConfig> steps;
        List<WorkflowConnector> connectors;
        try {
            steps = objectMapper.readValue(def.getSteps(), new TypeReference<>() {});
            String connectorsJson = def.getConnectors();
            connectors = (connectorsJson != null && !connectorsJson.isBlank())
                    ? objectMapper.readValue(connectorsJson, new TypeReference<>() {})
                    : List.of();
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
        return executor.execute(saved, steps, def.getName(), connectors);
    }

    public WorkflowAnalyticsDto analytics(String username) {
        long total     = runRepo.count();
        long completed = runRepo.countByUsernameAndStatus(username, WorkflowStatus.COMPLETED);
        long failed    = runRepo.countByUsernameAndStatus(username, WorkflowStatus.FAILED);
        long running   = runRepo.countByUsernameAndStatus(username, WorkflowStatus.RUNNING);
        long userTotal = runRepo.countByUsernameAndStatus(username, WorkflowStatus.COMPLETED)
                       + runRepo.countByUsernameAndStatus(username, WorkflowStatus.FAILED)
                       + runRepo.countByUsernameAndStatus(username, WorkflowStatus.RUNNING)
                       + runRepo.countByUsernameAndStatus(username, WorkflowStatus.PENDING);

        Double avgMs = runRepo.findAvgDurationMs(username);

        List<WorkflowAnalyticsDto.WorkflowUsageStat> byWorkflow = runRepo.findWorkflowUsageStats(username)
                .stream()
                .map(row -> new WorkflowAnalyticsDto.WorkflowUsageStat(
                        String.valueOf(row[0]),
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue()
                ))
                .toList();

        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        List<WorkflowAnalyticsDto.DailyRunStat> byDay = runRepo.findDailyRunsSince(username, since)
                .stream()
                .map(row -> new WorkflowAnalyticsDto.DailyRunStat(
                        String.valueOf(row[0]),
                        ((Number) row[1]).longValue()
                ))
                .toList();

        double completionRate = userTotal == 0 ? 0.0 : Math.round((double) completed / userTotal * 1000.0) / 10.0;

        return WorkflowAnalyticsDto.builder()
                .totalRuns(userTotal)
                .completedRuns(completed)
                .failedRuns(failed)
                .runningRuns(running)
                .completionRate(completionRate)
                .avgDurationMs(avgMs != null ? avgMs.longValue() : 0L)
                .byWorkflow(byWorkflow)
                .byDay(byDay)
                .build();
    }

    private WorkflowDefinitionDto toDto(WorkflowDefinition def) {
        List<WorkflowStepConfig> steps = List.of();
        try { steps = objectMapper.readValue(def.getSteps(), new TypeReference<>() {}); } catch (Exception ignored) {}
        List<WorkflowConnector> connectors = List.of();
        try {
            if (def.getConnectors() != null) connectors = objectMapper.readValue(def.getConnectors(), new TypeReference<>() {});
        } catch (Exception ignored) {}
        return WorkflowDefinitionDto.builder()
                .id(def.getId())
                .name(def.getName())
                .description(def.getDescription())
                .predefined(def.isPredefined())
                .team(def.isTeam())
                .autoTrigger(def.isAutoTrigger())
                .steps(steps)
                .connectors(connectors)
                .createdBy(def.getCreatedBy())
                .createdAt(def.getCreatedAt())
                .build();
    }

    private WorkflowRunDto toRunDto(WorkflowRun run) {
        Map<String, Object> results = Map.of();
        try {
            if (run.getResults() != null) results = objectMapper.readValue(run.getResults(), new TypeReference<>() {});
        } catch (Exception ignored) {}

        List<WorkflowStepConfig> steps = List.of();
        try { steps = objectMapper.readValue(run.getDefinition().getSteps(), new TypeReference<>() {}); } catch (Exception ignored) {}

        List<Integer> skipped = List.of();
        try {
            if (run.getSkippedSteps() != null) skipped = objectMapper.readValue(run.getSkippedSteps(), new TypeReference<>() {});
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
                .matterRef(run.getMatterRef())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .build();
    }
}
