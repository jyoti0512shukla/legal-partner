package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.model.dto.review.*;
import com.legalpartner.model.entity.*;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewPipelineService {

    private final ReviewPipelineRepository pipelineRepo;
    private final ReviewStageRepository stageRepo;
    private final MatterReviewRepository reviewRepo;
    private final ReviewActionRepository actionRepo;
    private final MatterRepository matterRepo;
    private final MatterMemberRepository memberRepo;
    private final DocumentMetadataRepository docRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    // ── Pipeline CRUD ──────────────────────────────────────────────────

    public List<PipelineDto> listPipelines() {
        return pipelineRepo.findAllByOrderByCreatedAtDesc().stream().map(this::toPipelineDto).toList();
    }

    @Transactional(readOnly = true)
    public PipelineDto getPipeline(UUID id) {
        return toPipelineDto(pipelineRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Transactional
    public PipelineDto createPipeline(PipelineCreateRequest req, String username) {
        UUID userId = userRepo.findByEmail(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
        ReviewPipeline pipeline = ReviewPipeline.builder()
                .name(req.name()).description(req.description()).isDefault(req.isDefault()).createdBy(userId).build();
        pipeline = pipelineRepo.save(pipeline);
        if (req.stages() != null) {
            for (StageDto s : req.stages()) {
                stageRepo.save(ReviewStage.builder()
                        .pipeline(pipeline).stageOrder(s.stageOrder()).name(s.name())
                        .requiredRole(s.requiredRole()).actions(s.actions()).autoNotify(s.autoNotify()).build());
            }
        }
        return toPipelineDto(pipeline);
    }

    @Transactional
    public PipelineDto updatePipeline(UUID id, PipelineCreateRequest req) {
        ReviewPipeline pipeline = pipelineRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        pipeline.setName(req.name());
        pipeline.setDescription(req.description());
        pipeline.setDefault(req.isDefault());
        pipeline.getStages().clear();
        if (req.stages() != null) {
            for (StageDto s : req.stages()) {
                pipeline.getStages().add(ReviewStage.builder()
                        .pipeline(pipeline).stageOrder(s.stageOrder()).name(s.name())
                        .requiredRole(s.requiredRole()).actions(s.actions()).autoNotify(s.autoNotify()).build());
            }
        }
        return toPipelineDto(pipelineRepo.save(pipeline));
    }

    public void deletePipeline(UUID id) { pipelineRepo.deleteById(id); }

    // ── Start a review on a matter/document ────────────────────────────

    @Transactional
    public MatterReviewDto startReview(UUID matterId, UUID documentId, UUID pipelineId, UUID userId) {
        ReviewPipeline pipeline = pipelineRepo.findById(pipelineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pipeline not found"));
        Matter matter = matterRepo.findById(matterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Matter not found"));
        List<ReviewStage> stages = stageRepo.findByPipelineIdOrderByStageOrderAsc(pipelineId);
        if (stages.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pipeline has no stages");

        DocumentMetadata doc = documentId != null ? docRepo.findById(documentId).orElse(null) : null;

        MatterReview review = MatterReview.builder()
                .matter(matter).document(doc).pipeline(pipeline)
                .currentStage(stages.get(0)).startedBy(userId).build();
        review = reviewRepo.save(review);
        log.info("Started review {} on matter {} with pipeline {}", review.getId(), matterId, pipeline.getName());
        return toReviewDto(review);
    }

    // ── Take action on a review ────────────────────────────────────────

    @Transactional
    public MatterReviewDto takeAction(UUID reviewId, ReviewActionRequest req, UUID userId) {
        MatterReview review = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"IN_PROGRESS".equals(review.getStatus()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review is not in progress");

        ReviewStage currentStage = review.getCurrentStage();
        String validActions = currentStage.getActions();
        if (!validActions.contains(req.action()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action '" + req.action() + "' not available at this stage");

        // Log the action
        actionRepo.save(ReviewAction.builder()
                .review(review).stage(currentStage).action(req.action())
                .actedBy(userId).notes(req.notes()).build());

        if ("APPROVE".equals(req.action())) {
            // Move to next stage
            List<ReviewStage> stages = stageRepo.findByPipelineIdOrderByStageOrderAsc(
                    review.getPipeline().getId());
            int nextOrder = currentStage.getStageOrder() + 1;
            ReviewStage nextStage = stages.stream()
                    .filter(s -> s.getStageOrder() == nextOrder).findFirst().orElse(null);

            if (nextStage != null) {
                review.setCurrentStage(nextStage);
                log.info("Review {} advanced to stage {}", reviewId, nextStage.getName());
            } else {
                // No more stages — review complete
                review.setStatus("APPROVED");
                review.setCompletedAt(Instant.now());
                log.info("Review {} completed (approved)", reviewId);
            }
        } else if ("RETURN".equals(req.action())) {
            // Move to previous stage
            List<ReviewStage> stages = stageRepo.findByPipelineIdOrderByStageOrderAsc(
                    review.getPipeline().getId());
            int prevOrder = currentStage.getStageOrder() - 1;
            ReviewStage prevStage = stages.stream()
                    .filter(s -> s.getStageOrder() == prevOrder).findFirst().orElse(null);
            if (prevStage != null) {
                review.setCurrentStage(prevStage);
                log.info("Review {} returned to stage {}", reviewId, prevStage.getName());
            }
        } else if ("SEND".equals(req.action())) {
            review.setStatus("SENT");
            review.setCompletedAt(Instant.now());
        }

        return toReviewDto(reviewRepo.save(review));
    }

    // ── Dashboard ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardDto getDashboard(UUID userId, String userRole) {
        // Get matters this user is on
        List<UUID> matterIds = memberRepo.findMatterIdsByUserId(userId);

        // Needs action: reviews where current stage matches user's role
        List<MatterReview> allInProgress = matterIds.isEmpty()
                ? List.of()
                : reviewRepo.findInProgressByMatterIds(matterIds);

        String roleForMatch = userRole.replace("ROLE_", "");

        List<MatterReviewDto> needsAction = allInProgress.stream()
                .filter(r -> r.getCurrentStage() != null && (
                        r.getCurrentStage().getRequiredRole() == null ||
                        r.getCurrentStage().getRequiredRole().equalsIgnoreCase(roleForMatch) ||
                        "ADMIN".equalsIgnoreCase(roleForMatch)))
                .map(this::toReviewDto)
                .toList();

        // Team activity: all in-progress on user's matters (excluding needs action)
        Set<UUID> needsActionIds = needsAction.stream().map(MatterReviewDto::id).collect(Collectors.toSet());
        List<MatterReviewDto> teamActivity = allInProgress.stream()
                .filter(r -> !needsActionIds.contains(r.getId()))
                .map(this::toReviewDto)
                .toList();

        // Recently completed
        List<MatterReviewDto> completed = reviewRepo.findByStatusOrderByStartedAtDesc("APPROVED").stream()
                .filter(r -> matterIds.contains(r.getMatter().getId()))
                .limit(10)
                .map(this::toReviewDto)
                .toList();

        return new DashboardDto(needsAction, teamActivity, completed);
    }

    // ── Review history for a matter ────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MatterReviewDto> getMatterReviews(UUID matterId) {
        return reviewRepo.findByMatterIdOrderByStartedAtDesc(matterId).stream()
                .map(this::toReviewDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewActionDto> getReviewActions(UUID reviewId) {
        return actionRepo.findByReviewIdOrderByCreatedAtDesc(reviewId).stream()
                .map(a -> {
                    String actorName = userRepo.findById(a.getActedBy())
                            .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail())
                            .orElse("Unknown");
                    return new ReviewActionDto(a.getId(), a.getStage().getName(),
                            a.getAction(), actorName, a.getNotes(), a.getCreatedAt());
                }).toList();
    }

    // ── DTO mapping ────────────────────────────────────────────────────

    private PipelineDto toPipelineDto(ReviewPipeline p) {
        List<StageDto> stages = stageRepo.findByPipelineIdOrderByStageOrderAsc(p.getId()).stream()
                .map(s -> new StageDto(s.getId(), s.getStageOrder(), s.getName(),
                        s.getRequiredRole(), s.getActions(), s.isAutoNotify()))
                .toList();
        return new PipelineDto(p.getId(), p.getName(), p.getDescription(), p.isDefault(), stages, p.getCreatedAt());
    }

    private MatterReviewDto toReviewDto(MatterReview r) {
        List<ReviewStage> stages = stageRepo.findByPipelineIdOrderByStageOrderAsc(r.getPipeline().getId());
        return new MatterReviewDto(r.getId(), r.getMatter().getId(), r.getMatter().getName(),
                r.getDocument() != null ? r.getDocument().getId() : null,
                r.getDocument() != null ? r.getDocument().getFileName() : null,
                r.getPipeline().getName(),
                r.getCurrentStage() != null ? r.getCurrentStage().getName() : "Complete",
                r.getCurrentStage() != null ? r.getCurrentStage().getStageOrder() : stages.size(),
                stages.size(), r.getStatus(),
                r.getCurrentStage() != null ? r.getCurrentStage().getRequiredRole() : null,
                r.getCurrentStage() != null ? r.getCurrentStage().getActions() : "",
                r.getStartedBy(), r.getStartedAt(), r.getCompletedAt());
    }
}
