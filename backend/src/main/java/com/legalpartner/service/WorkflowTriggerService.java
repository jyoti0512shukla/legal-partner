package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.event.DocumentIndexedEvent;
import com.legalpartner.model.dto.WorkflowStepConfig;
import com.legalpartner.model.entity.WorkflowDefinition;
import com.legalpartner.model.entity.WorkflowRun;
import com.legalpartner.model.enums.WorkflowStatus;
import com.legalpartner.repository.WorkflowDefinitionRepository;
import com.legalpartner.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

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
        List<WorkflowDefinition> triggered = definitionRepo.findByAutoTriggerTrue();
        if (triggered.isEmpty()) return;

        log.info("Document {} indexed — {} auto-trigger workflow(s) queued", event.documentId(), triggered.size());

        for (WorkflowDefinition def : triggered) {
            try {
                List<WorkflowStepConfig> steps = objectMapper.readValue(def.getSteps(), new TypeReference<>() {});
                WorkflowRun run = runRepo.save(WorkflowRun.builder()
                        .definition(def)
                        .documentId(event.documentId())
                        .username(event.uploadedBy())
                        .status(WorkflowStatus.PENDING)
                        .build());
                executor.execute(run, steps, def.getName());  // background — SSE emitter discarded
                log.info("Auto-triggered '{}' (runId={}) for document {}", def.getName(), run.getId(), event.documentId());
            } catch (Exception e) {
                log.error("Auto-trigger failed for workflow '{}': {}", def.getName(), e.getMessage());
            }
        }
    }
}
