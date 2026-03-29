package com.legalpartner.controller;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.model.dto.agent.FindingReviewRequest;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.service.AuditService;
import com.legalpartner.model.dto.agent.FindingSummaryDto;
import com.legalpartner.model.dto.agent.MatterFindingDto;
import com.legalpartner.model.entity.MatterFinding;
import com.legalpartner.model.enums.FindingSeverity;
import com.legalpartner.model.enums.FindingStatus;
import com.legalpartner.repository.MatterFindingRepository;
import com.legalpartner.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class FindingsController {

    private final MatterFindingRepository findingRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    // ── Global findings dashboard ─────────────────────────────────────
    @GetMapping("/api/v1/findings/dashboard")
    public java.util.Map<String, Object> globalDashboard() {
        List<Object[]> severityCounts = findingRepo.countAllBySeverity();
        long high = 0, medium = 0, low = 0;
        for (Object[] row : severityCounts) {
            FindingSeverity sev = (FindingSeverity) row[0];
            long count = (Long) row[1];
            switch (sev) {
                case HIGH -> high = count;
                case MEDIUM -> medium = count;
                case LOW -> low = count;
            }
        }
        long unreviewed = findingRepo.countByStatus(FindingStatus.NEW);
        List<MatterFindingDto> recent = findingRepo.findTop20ByOrderByCreatedAtDesc()
                .stream().map(this::toDto).toList();

        // Matters with most unreviewed findings
        List<Object[]> matterCounts = findingRepo.countUnreviewedByMatterAndSeverity();
        java.util.Map<UUID, java.util.Map<String, Object>> mattersMap = new java.util.LinkedHashMap<>();
        for (Object[] row : matterCounts) {
            UUID matterId = (UUID) row[0];
            String matterName = (String) row[1];
            FindingSeverity sev = (FindingSeverity) row[2];
            long count = (Long) row[3];
            mattersMap.computeIfAbsent(matterId, k -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("matterId", matterId);
                m.put("matterName", matterName);
                m.put("high", 0L);
                m.put("medium", 0L);
                m.put("low", 0L);
                return m;
            });
            mattersMap.get(matterId).put(sev.name().toLowerCase(), count);
        }

        return java.util.Map.of(
                "highCount", high,
                "mediumCount", medium,
                "lowCount", low,
                "unreviewedCount", unreviewed,
                "totalCount", high + medium + low,
                "recentFindings", recent,
                "matterRiskSummary", mattersMap.values().stream().toList()
        );
    }

    // ── Per-matter findings ───────────────────────────────────────────
    @GetMapping("/api/v1/matters/{matterId}/findings")
    public List<MatterFindingDto> list(
            @PathVariable UUID matterId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status) {
        List<MatterFinding> findings;
        if (severity != null) {
            findings = findingRepo.findByMatterIdAndSeverity(matterId, FindingSeverity.valueOf(severity));
        } else if (status != null) {
            findings = findingRepo.findByMatterIdAndStatus(matterId, FindingStatus.valueOf(status));
        } else {
            findings = findingRepo.findByMatterIdOrderByCreatedAtDesc(matterId);
        }
        return findings.stream().map(this::toDto).toList();
    }

    @GetMapping("/api/v1/matters/{matterId}/findings/summary")
    public FindingSummaryDto summary(@PathVariable UUID matterId) {
        List<Object[]> counts = findingRepo.countBySeverity(matterId);
        long high = 0, medium = 0, low = 0;
        for (Object[] row : counts) {
            FindingSeverity sev = (FindingSeverity) row[0];
            long count = (Long) row[1];
            switch (sev) {
                case HIGH -> high = count;
                case MEDIUM -> medium = count;
                case LOW -> low = count;
            }
        }
        long unreviewed = findingRepo.countByMatterIdAndStatus(matterId, FindingStatus.NEW);
        return new FindingSummaryDto(high, medium, low, unreviewed, high + medium + low);
    }

    @PatchMapping("/api/v1/matters/{matterId}/findings/{findingId}")
    public MatterFindingDto review(
            @PathVariable UUID matterId,
            @PathVariable UUID findingId,
            @Valid @RequestBody FindingReviewRequest req,
            Authentication auth) {
        MatterFinding finding = findingRepo.findById(findingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UUID userId = userRepo.findByEmail(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
        finding.setStatus(FindingStatus.valueOf(req.status()));
        finding.setReviewedBy(userId);
        finding.setReviewedAt(Instant.now());
        auditService.publish(AuditEvent.builder()
                .username(auth.getName()).action(AuditActionType.AGENT_FINDING_REVIEWED)
                .endpoint("/matters/" + matterId + "/findings/" + findingId)
                .queryText(finding.getTitle() + " → " + req.status())
                .success(true).build());
        return toDto(findingRepo.save(finding));
    }

    private MatterFindingDto toDto(MatterFinding f) {
        return new MatterFindingDto(
                f.getId(),
                f.getMatter().getId(),
                f.getDocument() != null ? f.getDocument().getId() : null,
                f.getDocument() != null ? f.getDocument().getFileName() : null,
                f.getFindingType().name(),
                f.getSeverity().name(),
                f.getClauseType(),
                f.getTitle(),
                f.getDescription(),
                f.getSectionRef(),
                f.getRelatedDocument() != null ? f.getRelatedDocument().getId() : null,
                f.getRelatedDocument() != null ? f.getRelatedDocument().getFileName() : null,
                f.getStatus().name(),
                f.getReviewedBy(),
                f.getReviewedAt(),
                f.getCreatedAt()
        );
    }
}
