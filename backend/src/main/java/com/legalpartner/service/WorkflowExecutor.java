package com.legalpartner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.*;
import com.legalpartner.model.entity.WorkflowRun;
import com.legalpartner.model.enums.WorkflowStatus;
import com.legalpartner.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutor {

    private final AiService aiService;
    private final ContractReviewService contractReviewService;
    private final WorkflowRunRepository runRepo;
    private final ObjectMapper objectMapper;

    public SseEmitter execute(WorkflowRun run, List<WorkflowStepConfig> steps, String workflowName) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — long running

        Thread.ofVirtual().start(() -> {
            Map<String, Object> results = new HashMap<>();
            UUID runId = run.getId();
            UUID docId = run.getDocumentId();
            String username = run.getUsername();

            try {
                updateStatus(runId, WorkflowStatus.RUNNING, 0, null);
                send(emitter, "workflow_start", Map.of(
                        "runId", runId.toString(),
                        "workflowName", workflowName,
                        "totalSteps", steps.size()
                ));

                for (int i = 0; i < steps.size(); i++) {
                    WorkflowStepConfig step = steps.get(i);
                    send(emitter, "step_start", Map.of(
                            "stepIndex", i,
                            "stepType", step.getType().name(),
                            "label", step.getLabel()
                    ));

                    Object result = executeStep(step, docId, username);
                    results.put(step.getType().name(), result);

                    updateStatus(runId, WorkflowStatus.RUNNING, i + 1, results);
                    send(emitter, "step_complete", Map.of(
                            "stepIndex", i,
                            "stepType", step.getType().name(),
                            "label", step.getLabel(),
                            "result", result
                    ));
                }

                updateStatus(runId, WorkflowStatus.COMPLETED, steps.size(), results);
                send(emitter, "workflow_complete", Map.of(
                        "runId", runId.toString(),
                        "results", results
                ));
                emitter.complete();

            } catch (Exception e) {
                log.error("Workflow run {} failed: {}", runId, e.getMessage(), e);
                try {
                    WorkflowRun failed = runRepo.findById(runId).orElseThrow();
                    failed.setStatus(WorkflowStatus.FAILED);
                    failed.setErrorMessage(e.getMessage());
                    failed.setCompletedAt(Instant.now());
                    runRepo.save(failed);
                } catch (Exception ignored) {}
                try {
                    send(emitter, "workflow_error", Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private Object executeStep(WorkflowStepConfig step, UUID docId, String username) {
        return switch (step.getType()) {
            case EXTRACT_KEY_TERMS -> aiService.extractKeyTerms(docId, username);
            case RISK_ASSESSMENT -> aiService.assessRisk(docId, username);
            case CLAUSE_CHECKLIST -> contractReviewService.review(
                    new ContractReviewRequest(docId, null), username);
        };
    }

    private void updateStatus(UUID runId, WorkflowStatus status, int currentStep, Map<String, Object> results) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            run.setCurrentStep(currentStep);
            if (results != null) {
                try {
                    run.setResults(objectMapper.writeValueAsString(results));
                } catch (Exception ignored) {}
            }
            if (status == WorkflowStatus.COMPLETED || status == WorkflowStatus.FAILED) {
                run.setCompletedAt(Instant.now());
            }
            runRepo.save(run);
        });
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        emitter.send(SseEmitter.event()
                .name(event)
                .data(json, MediaType.APPLICATION_JSON));
    }
}
