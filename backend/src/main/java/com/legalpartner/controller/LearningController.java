package com.legalpartner.controller;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.User;
import com.legalpartner.model.entity.learning.DraftingInsight;
import com.legalpartner.model.entity.learning.ReviewDispute;
import com.legalpartner.model.enums.DocumentType;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.service.MatterAccessService;
import com.legalpartner.service.learning.FirmClauseBankService;
import com.legalpartner.service.learning.FirmClauseBankService.FirmClauseView;
import com.legalpartner.service.learning.FirmNormsService;
import com.legalpartner.service.learning.InsightService;
import com.legalpartner.service.learning.LearningConfig;
import com.legalpartner.service.learning.LearningMetricsService;
import com.legalpartner.service.learning.ReviewCalibrationService;
import com.legalpartner.service.review.DraftManifestStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Firm learning loop API (docs/LEARNING_LOOP_ARCHITECTURE.md): the lawyer gates on what the
 * system learns — approving mined firm clauses and learned preferences — plus review answer
 * corrections and the metrics that show whether learning is helping.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LearningController {

    private final FirmClauseBankService clauseBank;
    private final InsightService insights;
    private final FirmNormsService norms;
    private final ReviewCalibrationService calibration;
    private final LearningMetricsService metrics;
    private final LearningConfig config;
    private final com.legalpartner.service.learning.LearningAccess access;
    private final DocumentMetadataRepository documents;
    private final DraftManifestStore manifests;
    private final MatterAccessService matterAccess;
    private final com.legalpartner.service.learning.EvalCaseExporter evalCases;

    public record ApproveRequest(String text, String position) {}
    public record InsightRequest(String clauseKey, String contractType, String content) {}
    public record StatusRequest(String status) {}

    // ── Settings the UI needs (so it hardcodes nothing) ─────────────────────────

    @GetMapping("/learning/settings")
    public Map<String, Object> settings(Authentication auth) {
        return Map.of(
                "enabled", config.isEnabled(),
                "positions", config.getPositions(),
                "stances", config.getStancePositionOrder().keySet().stream().filter(k -> !k.startsWith("_")).toList(),
                "insightStatuses", List.of(InsightService.PROPOSED, InsightService.ACTIVE, InsightService.RETIRED),
                "firmClauseStatuses", List.of(FirmClauseBankService.CANDIDATE, FirmClauseBankService.APPROVED,
                        FirmClauseBankService.REJECTED, FirmClauseBankService.RETIRED),
                "canCurate", access.canCurate(auth),
                "canDispute", access.canDispute(auth));
    }

    // ── Firm clause bank ────────────────────────────────────────────────────────

    @GetMapping("/learning/firm-clauses")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public List<FirmClauseView> firmClauses(@RequestParam(required = false) String status) {
        return clauseBank.list(status);
    }

    @PostMapping("/learning/firm-clauses/{id}/approve")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public FirmClauseView approve(@PathVariable UUID id, @RequestBody(required = false) ApproveRequest req, Authentication auth) {
        try {
            return clauseBank.approve(id, req == null ? null : req.text(), req == null ? null : req.position(), auth.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/learning/firm-clauses/{id}/reject")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public FirmClauseView reject(@PathVariable UUID id) {
        return setClauseStatus(id, FirmClauseBankService.REJECTED);
    }

    @PostMapping("/learning/firm-clauses/{id}/retire")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public FirmClauseView retire(@PathVariable UUID id) {
        return setClauseStatus(id, FirmClauseBankService.RETIRED);
    }

    @PostMapping("/learning/mine")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public FirmClauseBankService.MiningReport mine() {
        return clauseBank.mine();
    }

    private FirmClauseView setClauseStatus(UUID id, String status) {
        try {
            return clauseBank.setStatus(id, status);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ── Learned drafting preferences ───────────────────────────────────────────

    @GetMapping("/learning/insights")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public List<DraftingInsight> insights(@RequestParam(required = false) String status) {
        return insights.list(status);
    }

    @PostMapping("/learning/insights")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public DraftingInsight createInsight(@RequestBody InsightRequest req, Authentication auth) {
        if (req == null || blank(req.clauseKey()) || blank(req.content())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clauseKey and content are required");
        }
        return insights.createManual(req.clauseKey().trim().toUpperCase(),
                blank(req.contractType()) ? null : req.contractType().trim().toUpperCase(), req.content(), auth.getName());
    }

    @PostMapping("/learning/insights/{id}/status")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public DraftingInsight insightStatus(@PathVariable UUID id, @RequestBody StatusRequest req, Authentication auth) {
        Set<String> allowed = Set.of(InsightService.PROPOSED, InsightService.ACTIVE, InsightService.RETIRED);
        String status = req == null || req.status() == null ? "" : req.status().trim().toUpperCase();
        if (!allowed.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + allowed);
        try {
            return insights.setStatus(id, status, auth.getName());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ── Firm norms and metrics ─────────────────────────────────────────────────

    @GetMapping("/learning/norms/{documentType}")
    public FirmNormsService.Norms norms(@PathVariable String documentType) {
        try {
            return norms.compute(DocumentType.valueOf(documentType.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown document type " + documentType);
        }
    }

    @GetMapping("/learning/metrics")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public LearningMetricsService.Metrics metrics() {
        return metrics.compute();
    }

    /**
     * Signed AI drafts as eval briefs for eval/drafting ({@code --briefs}). Anonymized by default;
     * {@code anonymize=false} returns client data and must stay on firm infrastructure.
     */
    @GetMapping("/learning/eval-cases")
    @PreAuthorize("@learningAccess.canCurate(authentication)")
    public Map<String, Object> evalCases(@RequestParam(defaultValue = "true") boolean anonymize,
                                         @RequestParam(defaultValue = "50") int limit) {
        return evalCases.export(anonymize, Math.max(1, Math.min(limit, 500)));
    }

    // ── Review corrections ─────────────────────────────────────────────────────

    @PostMapping("/ai/risk-assessment/{docId}/disputes")
    @PreAuthorize("@learningAccess.canDispute(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDispute dispute(@PathVariable UUID docId, @RequestBody ReviewCalibrationService.DisputeRequest req,
                                 Authentication auth) {
        DocumentMetadata doc = documents.findById(docId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        if (doc.getMatter() != null) {
            User user = matterAccess.resolveUser(auth);
            matterAccess.requireMembership(doc.getMatter().getId(), user.getId(), user.getRole());
        }
        // Same contract-type key the review used: the draft's review type, else the document type.
        String contractType = manifests.read(docId).map(m -> m.reviewType())
                .orElse(doc.getDocumentType() != null ? doc.getDocumentType().name() : null);
        try {
            return calibration.dispute(docId, contractType, req, auth.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
