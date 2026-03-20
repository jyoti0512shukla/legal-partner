package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.dto.*;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.WorkflowRun;
import com.legalpartner.model.enums.WorkflowStatus;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutor {

    private final AiService aiService;
    private final ContractReviewService contractReviewService;
    private final WorkflowRunRepository runRepo;
    private final ConnectorService connectorService;
    private final WorkflowQualityScorer qualityScorer;
    private final WorkflowContextService workflowContextService;
    private final DocumentMetadataRepository documentMetadataRepository;
    private final ObjectMapper objectMapper;

    public SseEmitter execute(WorkflowRun run, List<WorkflowStepConfig> steps, String workflowName) {
        return execute(run, steps, workflowName, List.of());
    }

    public SseEmitter execute(WorkflowRun run, List<WorkflowStepConfig> steps, String workflowName,
                              List<WorkflowConnector> connectors) {
        SseEmitter emitter = new SseEmitter(0L);

        Thread.ofVirtual().start(() -> {
            Map<String, Object> results = new HashMap<>();
            List<Integer> skippedIndices = new ArrayList<>();
            UUID runId = run.getId();
            UUID docId = run.getDocumentId();
            String username = run.getUsername();

            // Load document metadata once — used for RAG context filtering (null for draft-only runs)
            DocumentMetadata docMeta = (docId != null) ? documentMetadataRepository.findById(docId).orElse(null) : null;

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

                    // Execute with quality loop (replaces plain exception-retry)
                    Object result = executeWithQualityLoop(step, docId, username, results, runId, i, docMeta, emitter);
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

                if (connectors != null && !connectors.isEmpty()) {
                    runRepo.findById(runId).ifPresent(completedRun ->
                            connectorService.fireAll(completedRun, connectors, results, workflowName));
                }

            } catch (Exception e) {
                log.error("Workflow run {} failed: {}", runId, e.getMessage(), e);
                persistFailure(runId, e.getMessage(), results, skippedIndices);
                try { send(emitter, "workflow_error", Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error")); }
                catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ── Quality loop (replaces exception-only retry) ──────────────────────────

    private Object executeWithQualityLoop(WorkflowStepConfig step, UUID docId, String username,
                                          Map<String, Object> priorResults, UUID runId, int stepIndex,
                                          DocumentMetadata docMeta, SseEmitter emitter) throws Exception {
        int maxIter = Math.max(1, Math.min(step.getMaxIterations(), 3));

        // Retrieve RAG context once before the loop — same context is enriched by feedback each pass
        String ragContext = workflowContextService.getContextForStep(
                step.getType(), docMeta, priorResults, objectMapper);
        if (!ragContext.isBlank()) {
            log.info("Workflow {}: step {} RAG context: {} chars", runId, step.getType(), ragContext.length());
        }

        String feedbackContext = null;
        Object result = null;
        Exception lastException = null;

        for (int iter = 0; iter < maxIter; iter++) {
            if (iter > 0) {
                // Notify frontend that this step is being refined
                try {
                    send(emitter, "step_iteration", Map.of(
                            "stepIndex", stepIndex,
                            "stepType", step.getType().name(),
                            "iteration", iter + 1,
                            "maxIterations", maxIter
                    ));
                } catch (Exception ignored) {}
                Thread.sleep(400);
                log.info("Workflow {}: step {} ({}) refinement pass {}/{}", runId, stepIndex, step.getType(), iter + 1, maxIter);
            }

            try {
                result = executeStep(step, docId, username, priorResults, ragContext, feedbackContext);
                lastException = null;
            } catch (Exception e) {
                lastException = e;
                log.warn("Workflow {}: step {} ({}) failed on iter {}: {}", runId, stepIndex, step.getType(), iter + 1, e.getMessage());
                if (iter == maxIter - 1) throw e;
                feedbackContext = "Previous attempt failed with error: " + e.getMessage() + ". Retry carefully.";
                continue;
            }

            // Score quality — if passing or last iteration, accept
            WorkflowQualityScorer.QualityScore quality = qualityScorer.score(step.getType(), result, objectMapper);
            log.info("Workflow {}: step {} iter {}/{} quality={}/100 gaps={}",
                    runId, step.getType(), iter + 1, maxIter, quality.score(), quality.gaps());

            if (quality.isPassing() || iter == maxIter - 1) break;

            // Build targeted feedback for next iteration
            feedbackContext = buildFeedbackContext(quality, iter + 1);
        }

        if (lastException != null) throw lastException;
        return result;
    }

    private Object executeStep(WorkflowStepConfig step, UUID docId, String username,
                               Map<String, Object> priorResults, String ragContext, String feedbackContext) {
        // For draft-only runs (no document), inject the DRAFT_CLAUSE output as the "contract text"
        String draftedText = extractDraftedText(priorResults);
        return switch (step.getType()) {
            case EXTRACT_KEY_TERMS   -> docId != null
                    ? aiService.extractKeyTerms(docId, username)
                    : Map.of("note", "Key terms extraction requires an uploaded document");
            case RISK_ASSESSMENT     -> aiService.assessRiskWithContext(docId, username, ragContext, feedbackContext, draftedText);
            case CLAUSE_CHECKLIST    -> docId != null
                    ? contractReviewService.review(new ContractReviewRequest(docId, null), username)
                    : Map.of("clauses", List.of(), "note", "Clause checklist requires an uploaded document");
            case GENERATE_SUMMARY    -> aiService.generateWorkflowSummary(docId, priorResults, username, draftedText);
            case REDLINE_SUGGESTIONS -> aiService.generateRedlinesWithContext(docId, priorResults, username, ragContext, feedbackContext, draftedText);
            case DRAFT_CLAUSE        -> aiService.draftClauseForWorkflow(docId, step.getParams(), username, ragContext, feedbackContext);
        };
    }

    /** Extracts drafted clause text from prior DRAFT_CLAUSE step result (for document-less runs). */
    @SuppressWarnings("unchecked")
    private String extractDraftedText(Map<String, Object> priorResults) {
        Object draftRaw = priorResults.get("DRAFT_CLAUSE");
        if (draftRaw == null) return null;
        try {
            String json = objectMapper.writeValueAsString(draftRaw);
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            return node.path("content").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildFeedbackContext(WorkflowQualityScorer.QualityScore quality, int attempt) {
        if (quality.gaps().isEmpty()) return null;
        return "=== REFINEMENT REQUIRED (Pass " + attempt + ") ===\n" +
               "Your previous output was incomplete. Address ALL of the following:\n" +
               quality.gaps().stream().map(g -> "• " + g).collect(Collectors.joining("\n")) +
               "\n=== PRODUCE AN IMPROVED VERSION NOW ===\n";
    }

    // ── Condition evaluation ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private boolean evaluateCondition(WorkflowCondition condition, Map<String, Object> results) {
        if (condition == null || condition.getField() == null) return true;
        try {
            String json = objectMapper.writeValueAsString(results);
            Map<String, Object> jsonMap = objectMapper.readValue(json, new TypeReference<>() {});

            String[] parts = condition.getField().split("\\.");
            Object current = jsonMap;
            for (String part : parts) {
                if (current instanceof Map) current = ((Map<String, Object>) current).get(part);
                else return false;
            }
            if (current == null) return false;

            String actual   = String.valueOf(current).toUpperCase();
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

    // ── Persistence helpers ───────────────────────────────────────────────────

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
