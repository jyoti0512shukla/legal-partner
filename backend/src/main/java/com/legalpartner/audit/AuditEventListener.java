package com.legalpartner.audit;

import com.legalpartner.model.entity.AuditLog;
import com.legalpartner.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventListener {

    private final AuditLogRepository auditLogRepository;

    @Async
    @EventListener
    public void handleAuditEvent(AuditEvent event) {
        try {
            AuditLog logEntry = AuditLog.builder()
                    .username(event.getUsername())
                    .userRole(event.getUserRole())
                    .action(event.getAction())
                    .endpoint(event.getEndpoint())
                    .httpMethod(event.getHttpMethod())
                    .documentId(event.getDocumentId())
                    .queryText(event.getQueryText())
                    .retrievedDocIds(event.getRetrievedDocIds())
                    .responseTimeMs(event.getResponseTimeMs())
                    .ipAddress(event.getIpAddress())
                    .success(event.isSuccess())
                    .errorMessage(event.getErrorMessage())
                    .build();
            auditLogRepository.save(logEntry);
        } catch (Exception e) {
            log.error("Failed to persist audit event: {}", e.getMessage(), e);
        }
    }
}
