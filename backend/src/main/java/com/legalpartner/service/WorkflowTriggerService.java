package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.event.DocumentIndexedEvent;
import com.legalpartner.event.DraftCompletedEvent;
import com.legalpartner.model.dto.WorkflowStepConfig;
import com.legalpartner.model.dto.WorkflowTrigger;
import com.legalpartner.model.entity.WorkflowDefinition;
import com.legalpartner.model.entity.WorkflowRun;
import com.legalpartner.model.enums.WorkflowStatus;
import com.legalpartner.model.enums.WorkflowTriggerEvent;
import com.legalpartner.repository.WorkflowDefinitionRepository;
import com.legalpartner.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowTriggerService {

    private final WorkflowDefinitionRepository definitionRepo;
    private final WorkflowRunRepository runRepo;
    private final WorkflowExecutor executor;
    private final ObjectMapper objectMapper;

    @Async
    @EventListener
    public void onDocumentIndexed(DocumentIndexedEvent event) {
        triggerWorkflows(WorkflowTriggerEvent.DOCUMENT_INDEXED, event.documentId(), event.uploadedBy());
    }

    @Async
    @EventListener
    public void onDraftCompleted(DraftCompletedEvent event) {
        triggerWorkflows(WorkflowTriggerEvent.DRAFT_COMPLETED, event.documentId(), event.username());
    }

    private void triggerWorkflows(WorkflowTriggerEvent eventType, UUID documentId, String username) {
        List<WorkflowDefinition> all = definitionRepo.findAll();
        List<WorkflowDefinition> triggered = all.stream()
                .filter(def -> matchesTrigger(def, eventType))
                .toList();

        if (triggered.isEmpty()) return;

        log.info("Event {} for doc {} — {} workflow(s) triggered", eventType, documentId, triggered.size());

        for (WorkflowDefinition def : triggered) {
            try {
                List<WorkflowStepConfig> steps = objectMapper.readValue(def.getSteps(), new TypeReference<>() {});
                WorkflowRun run = runRepo.save(WorkflowRun.builder()
                        .definition(def)
                        .documentId(documentId)
                        .username(username)
                        .status(WorkflowStatus.PENDING)
                        .build());
                executor.execute(run, steps, def.getName());
                log.info("Auto-triggered '{}' (runId={}) on {} for document {}",
                        def.getName(), run.getId(), eventType, documentId);
            } catch (Exception e) {
                log.error("Auto-trigger failed for workflow '{}': {}", def.getName(), e.getMessage());
            }
        }
    }

    private boolean matchesTrigger(WorkflowDefinition def, WorkflowTriggerEvent eventType) {
        // Check new triggers JSONB first
        try {
            List<WorkflowTrigger> triggers = objectMapper.readValue(
                    def.getTriggers(), new TypeReference<List<WorkflowTrigger>>() {});
            if (!triggers.isEmpty()) {
                return triggers.stream().anyMatch(t -> t.getEvent() == eventType);
            }
        } catch (Exception e) {
            log.debug("Failed to parse triggers for workflow '{}': {}", def.getName(), e.getMessage());
        }
        // Fallback to legacy autoTrigger boolean (only matches DOCUMENT_INDEXED)
        return def.isAutoTrigger() && eventType == WorkflowTriggerEvent.DOCUMENT_INDEXED;
    }
}
