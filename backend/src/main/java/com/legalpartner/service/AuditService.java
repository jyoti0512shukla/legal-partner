package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.model.dto.AuditLogEntry;
import com.legalpartner.model.dto.AuditStats;
import com.legalpartner.model.entity.AuditLog;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.repository.AuditLogRepository;
import com.opencsv.CSVWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public void publish(AuditEvent event) {
        eventPublisher.publishEvent(event);
    }

    public Page<AuditLogEntry> getLogs(
            String username, String userRole, AuditActionType action,
            Instant from, Instant to, UUID documentId,
            Pageable pageable
    ) {
        return repository.findFiltered(username, userRole, action, from, to, documentId, pageable)
                .map(this::toEntry);
    }

    public List<String> getDistinctUsernames(Instant from, Instant to) {
        return repository.findDistinctUsernames(from, to);
    }

    public AuditStats getStats(Instant from, Instant to) {
        long total = repository.count();
        long uploads = repository.countByAction(AuditActionType.DOCUMENT_UPLOAD);
        long queries = repository.countByAction(AuditActionType.AI_QUERY);
        long comparisons = repository.countByAction(AuditActionType.AI_COMPARE);
        long risks = repository.countByAction(AuditActionType.RISK_ASSESSMENT);

        Map<String, Long> byUser = repository.countByUserBetween(from, to).stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));

        Map<String, Long> byDay = repository.countByDayBetween(from, to).stream()
                .collect(Collectors.toMap(r -> r[0].toString(), r -> (Long) r[1]));

        return new AuditStats(total, uploads, queries, comparisons, risks, byUser, byDay);
    }

    public byte[] exportCsv(String username, String userRole, AuditActionType action, Instant from, Instant to, UUID documentId) {
        List<AuditLog> logs = repository.findFilteredAll(username, userRole, action, from, to, documentId);
        StringWriter sw = new StringWriter();
        try (CSVWriter writer = new CSVWriter(sw)) {
            writer.writeNext(new String[]{
                    "ID", "Timestamp", "Username", "Role", "Action",
                    "Endpoint", "Method", "Document ID", "Success", "Response Time (ms)"
            });
            for (AuditLog log : logs) {
                writer.writeNext(new String[]{
                        log.getId().toString(),
                        log.getTimestamp().toString(),
                        log.getUsername(),
                        log.getUserRole(),
                        log.getAction().name(),
                        log.getEndpoint(),
                        log.getHttpMethod(),
                        log.getDocumentId() != null ? log.getDocumentId().toString() : "",
                        String.valueOf(log.isSuccess()),
                        log.getResponseTimeMs() != null ? log.getResponseTimeMs().toString() : ""
                });
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to export audit CSV", e);
        }
        return sw.toString().getBytes(StandardCharsets.UTF_8);
    }

    public List<AuditLogEntry> getRecentActivity() {
        return repository.findTop10ByOrderByTimestampDesc().stream()
                .map(this::toEntry)
                .toList();
    }

    private AuditLogEntry toEntry(AuditLog log) {
        return new AuditLogEntry(
                log.getId(), log.getTimestamp(), log.getUsername(), log.getUserRole(),
                log.getAction().name(), log.getEndpoint(), log.getDocumentId(),
                log.getQueryText(), log.getResponseTimeMs(), log.isSuccess(), log.getErrorMessage()
        );
    }
}
