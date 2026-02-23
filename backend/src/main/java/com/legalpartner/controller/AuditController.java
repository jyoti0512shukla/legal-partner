package com.legalpartner.controller;

import com.legalpartner.model.dto.AuditLogEntry;
import com.legalpartner.model.dto.AuditStats;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/logs")
    public Page<AuditLogEntry> logs(
            @RequestParam(required = false) String user,
            @RequestParam(required = false) AuditActionType action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID documentId,
            Pageable pageable
    ) {
        Instant fromDate = from != null ? from : Instant.parse("2020-01-01T00:00:00Z");
        Instant toDate = to != null ? to : Instant.now();
        return auditService.getLogs(user, action, fromDate, toDate, documentId, pageable);
    }

    @GetMapping("/stats")
    public AuditStats stats(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        Instant fromDate = from != null ? from : Instant.parse("2020-01-01T00:00:00Z");
        Instant toDate = to != null ? to : Instant.now();
        return auditService.getStats(fromDate, toDate);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        Instant fromDate = from != null ? from : Instant.parse("2020-01-01T00:00:00Z");
        Instant toDate = to != null ? to : Instant.now();
        byte[] csv = auditService.exportCsv(fromDate, toDate);
        String filename = "audit_log_" + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @GetMapping("/recent")
    public List<AuditLogEntry> recent() {
        return auditService.getRecentActivity();
    }
}
