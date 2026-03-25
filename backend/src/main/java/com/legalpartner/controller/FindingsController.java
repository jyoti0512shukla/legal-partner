package com.legalpartner.controller;

import com.legalpartner.model.dto.agent.FindingReviewRequest;
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
@RequestMapping("/api/v1/matters/{matterId}/findings")
@RequiredArgsConstructor
public class FindingsController {

    private final MatterFindingRepository findingRepo;
    private final UserRepository userRepo;

    @GetMapping
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

    @GetMapping("/summary")
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

    @PatchMapping("/{findingId}")
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
