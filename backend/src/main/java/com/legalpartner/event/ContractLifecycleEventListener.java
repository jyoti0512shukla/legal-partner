package com.legalpartner.event;

import com.legalpartner.model.enums.ContractStatus;
import com.legalpartner.service.ContractLifecycleService;
import com.legalpartner.service.DeadlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContractLifecycleEventListener {

    private final DeadlineService deadlineService;
    private final ContractLifecycleService lifecycleService;

    @Async
    @EventListener
    public void onDocumentExecuted(DocumentExecutedEvent event) {
        try {
            // Extract deadlines from the executed contract
            deadlineService.extractDeadlines(event.documentId(), event.username());

            // Transition EXECUTED → ACTIVE
            lifecycleService.transitionStatus(event.documentId(), ContractStatus.ACTIVE, event.username());

            log.info("Processed execution event for doc {}: deadlines extracted, status → ACTIVE", event.documentId());
        } catch (Exception e) {
            log.error("Failed to process execution event for doc {}: {}", event.documentId(), e.getMessage());
        }
    }
}
