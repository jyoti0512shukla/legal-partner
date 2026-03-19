package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutor {

    private final AiService aiService;
    private final ContractReviewService contractReviewService;
    private final WorkflowRunRepository runRepo;
    private final ObjectMapper objectMapper;

    public SseEmitter execute(WorkflowRun run, List<WorkflowStepConfig> steps, String workflowName) {
        SseEmitter emitter = new SseEmitter(0L);

        Thread.ofVirtual().start(() -> {
            Map<String, Object> results = new HashMap<>();
            List<Integer> skippedIndices = new ArrayList<>();
            UUID runId = run.getId();
            UUID docId = run.getDocumentId();
            String username = run.getUsername();

            try {
                updateStatus(runId, WorkflowStatus.RUNNING, 0, null, skippedIndices);
                send(emitter, "workflow_start", Map.of(
                        "runId", runId.toString(),
                        "workflowName", workflowName,
                        "totalSteps", steps.size()
                ));

                for (int i = 0; i < steps.size(); i++) {
                    WorkflowStepConfig step = steps.get(i);

                    // Evaluate condition against prior results
                    if (!evaluateCondition(step.getCondition(), results)) {
                        skippedIndices.add(i);
                        send(emitter, "step_skipped", Map.of(
                                "stepIndex", i,
                                "stepType", step.getType().name(),
                                "label", step.getLabel(),
                                "reason", "Condition not met"
                        ));
                        log.info("Workflow {}: step {} ({}) skipped — condition not met", runId, i, step.getType());
                        continue;
                    }

                    send(emitter, "step_start", Map.of(
                            "stepIndex", i,
                            "stepType", step.getType().name(),
                            "label", step.getLabel()
                    ));

                    // Execute with retry
                    Object result = executeWithRetry(step, docId, username, results, runId, i);
                    results.put(step.getType().name(), result);

                    updateStatus(runId, WorkflowStatus.RUNNING, i + 1, results, skippedIndices);
                    send(emitter, "step_complete", Map.of(
                            "stepIndex", i,
                            "stepType", step.getType().name(),
                            "label", step.getLabel(),
                            "result", result
                    ));
                }

                updateStatus(runId, WorkflowStatus.COMPLETED, steps.size(), results, skippedIndices);
                send(emitter, "workflow_complete", Map.of(
                        "runId", runId.toString(),
                        "results", results,
                        "skippedSteps", skippedIndices
                ));
                emitter.complete();

            } catch (Exception e) {
                log.error("Workflow run {} failed: {}", runId, e.getMessage(), e);
                persistFailure(runId, e.getMessage(), results, skippedIndices);
                try {
                    send(emitter, "workflow_error", Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private Object executeWithRetry(WorkflowStepConfig step, UUID docId, String username,
                                    Map<String, Object> priorResults, UUID runId, int stepIndex) throws Exception {
        int maxAttempts = 1 + Math.max(0, Math.min(step.getRetryCount(), 3));
        Exception lastException = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("Workflow {}: retrying step {} ({}) — attempt {}/{}", runId, stepIndex, step.getType(), attempt + 1, maxAttempts);
                    Thread.sleep(1000L * attempt);
                }
                return executeStep(step, docId, username, priorResults);
            } catch (Exception e) {
                lastException = e;
                log.warn("Workflow {}: step {} ({}) failed attempt {}: {}", runId, stepIndex, step.getType(), attempt + 1, e.getMessage());
            }
        }
        throw lastException != null ? lastException : new RuntimeException("Step failed after " + maxAttempts + " attempts");
    }

    private Object executeStep(WorkflowStepConfig step, UUID docId, String username,
                               Map<String, Object> priorResults) {
        return switch (step.getType()) {
            case EXTRACT_KEY_TERMS -> aiService.extractKeyTerms(docId, username);
            case RISK_ASSESSMENT   -> aiService.assessRisk(docId, username);
            case CLAUSE_CHECKLIST  -> contractReviewService.review(new ContractReviewRequest(docId, null), username);
            case GENERATE_SUMMARY  -> aiService.generateWorkflowSummary(docId, priorResults, username);
            case REDLINE_SUGGESTIONS -> aiService.generateRedlines(docId, priorResults, username);
        };
    }

    /**
     * Evaluate a step condition against prior results.
     * Field is a dot-path into the results map serialized as JSON, e.g. "RISK_ASSESSMENT.overallRisk".
     * Returns true (run the step) when condition is null or satisfied.
     */
    @SuppressWarnings("unchecked")
    private boolean evaluateCondition(WorkflowCondition condition, Map<String, Object> results) {
        if (condition == null || condition.getField() == null) return true;
        try {
            // Serialize to JSON and back to get a plain Map<String,Object> (handles DTO objects)
            String json = objectMapper.writeValueAsString(results);
            Map<String, Object> jsonMap = objectMapper.readValue(json, new TypeReference<>() {});

            String[] parts = condition.getField().split("\\.");
            Object current = jsonMap;
            for (String part : parts) {
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(part);
                } else {
                    return false;
                }
            }
            if (current == null) return false;

            String actual = String.valueOf(current).toUpperCase();
            String expected = condition.getValue().toUpperCase();

            return switch (condition.getOp().toLowerCase()) {
                case "eq"  -> actual.equals(expected);
                case "neq" -> !actual.equals(expected);
                case "in"  -> Arrays.stream(expected.split(",")).map(String::trim).anyMatch(v -> v.equals(actual));
                default    -> true;
            };
        } catch (Exception e) {
            log.warn("Condition evaluation failed ({}), running step anyway", e.getMessage());
            return true;
        }
    }

    private void updateStatus(UUID runId, WorkflowStatus status, int currentStep,
                              Map<String, Object> results, List<Integer> skipped) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            run.setCurrentStep(currentStep);
            if (results != null) {
                try { run.setResults(objectMapper.writeValueAsString(results)); } catch (Exception ignored) {}
            }
            if (skipped != null) {
                try { run.setSkippedSteps(objectMapper.writeValueAsString(skipped)); } catch (Exception ignored) {}
            }
            if (status == WorkflowStatus.COMPLETED || status == WorkflowStatus.FAILED) {
                run.setCompletedAt(Instant.now());
            }
            runRepo.save(run);
        });
    }

    private void persistFailure(UUID runId, String message, Map<String, Object> results, List<Integer> skipped) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setStatus(WorkflowStatus.FAILED);
            run.setErrorMessage(message);
            run.setCompletedAt(Instant.now());
            if (results != null) {
                try { run.setResults(objectMapper.writeValueAsString(results)); } catch (Exception ignored) {}
            }
            if (skipped != null) {
                try { run.setSkippedSteps(objectMapper.writeValueAsString(skipped)); } catch (Exception ignored) {}
            }
            runRepo.save(run);
        });
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
    }
}
