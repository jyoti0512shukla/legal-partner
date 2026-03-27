package com.legalpartner.controller;

import com.legalpartner.model.dto.review.*;
import com.legalpartner.repository.UserRepository;
import com.legalpartner.service.ReviewPipelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/review-pipelines")
@RequiredArgsConstructor
public class ReviewPipelineController {

    private final ReviewPipelineService service;
    private final UserRepository userRepo;

    @GetMapping
    public List<PipelineDto> list() { return service.listPipelines(); }

    @GetMapping("/{id}")
    public PipelineDto get(@PathVariable UUID id) { return service.getPipeline(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public PipelineDto create(@Valid @RequestBody PipelineCreateRequest req, Authentication auth) {
        return service.createPipeline(req, auth.getName());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public PipelineDto update(@PathVariable UUID id, @Valid @RequestBody PipelineCreateRequest req) {
        return service.updatePipeline(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public void delete(@PathVariable UUID id) { service.deletePipeline(id); }

    // ── Reviews ──────────────────────────────────────────────────────

    @PostMapping("/reviews/start")
    @ResponseStatus(HttpStatus.CREATED)
    public MatterReviewDto startReview(@RequestParam UUID matterId,
                                        @RequestParam(required = false) UUID documentId,
                                        @RequestParam UUID pipelineId,
                                        Authentication auth) {
        UUID userId = userRepo.findByEmail(auth.getName()).orElseThrow().getId();
        return service.startReview(matterId, documentId, pipelineId, userId);
    }

    @PostMapping("/reviews/{reviewId}/action")
    public MatterReviewDto takeAction(@PathVariable UUID reviewId,
                                       @Valid @RequestBody ReviewActionRequest req,
                                       Authentication auth) {
        UUID userId = userRepo.findByEmail(auth.getName()).orElseThrow().getId();
        return service.takeAction(reviewId, req, userId);
    }

    @GetMapping("/reviews/matter/{matterId}")
    public List<MatterReviewDto> getMatterReviews(@PathVariable UUID matterId) {
        return service.getMatterReviews(matterId);
    }

    @GetMapping("/reviews/{reviewId}/actions")
    public List<ReviewActionDto> getReviewActions(@PathVariable UUID reviewId) {
        return service.getReviewActions(reviewId);
    }

    // ── Dashboard ────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public DashboardDto getDashboard(Authentication auth) {
        var user = userRepo.findByEmail(auth.getName()).orElseThrow();
        return service.getDashboard(user.getId(), user.getRole().name());
    }
}
