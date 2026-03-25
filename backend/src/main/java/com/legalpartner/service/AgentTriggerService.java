package com.legalpartner.service;

import com.legalpartner.agent.MatterDocumentEvent;
import com.legalpartner.model.entity.AgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentTriggerService {

    private final MatterAgentService agentService;
    private final AgentConfigService configService;

    @Async
    @EventListener
    public void onMatterDocumentEvent(MatterDocumentEvent event) {
        AgentConfig config = configService.getConfig();
        if (!config.isAutoAnalyzeOnUpload()) return;

        log.info("Agent trigger: {} event for matter {}", event.eventType(), event.matterId());

        if ("LINKED".equals(event.eventType()) && event.documentId() != null) {
            agentService.analyzeDocument(event.matterId(), event.documentId(), event.triggeredBy());
        } else if ("PLAYBOOK_CHANGED".equals(event.eventType())) {
            agentService.reanalyzeAllDocuments(event.matterId(), event.triggeredBy());
        }
    }
}
