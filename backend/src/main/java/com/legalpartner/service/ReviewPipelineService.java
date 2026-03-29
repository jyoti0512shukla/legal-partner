package com.legalpartner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.audit.AuditEvent;
import com.legalpartner.config.MailProperties;
import com.legalpartner.integration.SlackWebhookProvider;
import com.legalpartner.integration.MicrosoftTeamsProvider;
import com.legalpartner.model.dto.review.*;
import com.legalpartner.model.entity.*;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.model.enums.MatterMemberRole;
import com.legalpartner.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
    private final IntegrationConnectionRepository integrationRepo;
    private final SlackWebhookProvider slackProvider;
    private final MicrosoftTeamsProvider teamsProvider;
    private final MailProperties mailProps;
    @Nullable
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;

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

        // Notify matter members who match the first stage's required role
        if (stages.get(0).isAutoNotify()) {
            String startedByName = userRepo.findById(userId).map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail()).orElse("Someone");
            notifyStageMembers(matter, stages.get(0), pipeline.getName(),
                    doc != null ? doc.getFileName() : null, startedByName, "started");
        }
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

        String actorName = userRepo.findById(userId)
                .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail()).orElse("Someone");
        Matter matter = review.getMatter();
        String pipelineName = review.getPipeline().getName();
        String docName = review.getDocument() != null ? review.getDocument().getFileName() : null;

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
                if (nextStage.isAutoNotify()) {
                    notifyStageMembers(matter, nextStage, pipelineName, docName, actorName, "approved");
                }
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
                if (prevStage.isAutoNotify()) {
                    notifyStageMembers(matter, prevStage, pipelineName, docName, actorName, "returned");
                }
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

    // ── Review stage notifications ────────────────────────────────────

    private void notifyStageMembers(Matter matter, ReviewStage stage, String pipelineName,
                                     String docName, String actorName, String action) {
        List<MatterMember> recipients = findMembersForStage(matter.getId(), stage.getRequiredRole());
        if (recipients.isEmpty()) {
            log.info("No matter members match role '{}' for stage '{}' — skipping notification",
                    stage.getRequiredRole(), stage.getName());
            return;
        }

        String subject = String.format("Review %s: %s → %s", action, pipelineName, stage.getName());
        String message = buildReviewMessage(pipelineName, stage.getName(), docName, matter.getName(), actorName, action);

        for (MatterMember member : recipients) {
            if (member.getUser() == null) continue;
            UUID memberId = member.getUser().getId();

            // Slack
            try {
                var conn = integrationRepo.findByUserIdAndProvider(memberId, "SLACK");
                if (conn.isPresent()) {
                    var config = objectMapper.readTree(conn.get().getConfig());
                    String webhookUrl = config.has("webhookUrl") ? config.get("webhookUrl").asText() : null;
                    if (webhookUrl != null && !webhookUrl.isBlank()) {
                        slackProvider.sendNotification(webhookUrl, message);
                        log.info("Review Slack notification sent to {}", member.getEmail());
                    }
                }
            } catch (Exception e) {
                log.warn("Review Slack to {} failed: {}", member.getEmail(), e.getMessage());
            }

            // Teams
            try {
                var conn = integrationRepo.findByUserIdAndProvider(memberId, "MICROSOFT_TEAMS");
                if (conn.isPresent()) {
                    var config = objectMapper.readTree(conn.get().getConfig());
                    String webhookUrl = config.has("webhookUrl") ? config.get("webhookUrl").asText() : null;
                    if (webhookUrl != null && !webhookUrl.isBlank()) {
                        teamsProvider.sendNotification(webhookUrl, subject, message.replace("\n", "<br>"));
                        log.info("Review Teams notification sent to {}", member.getEmail());
                    }
                }
            } catch (Exception e) {
                log.warn("Review Teams to {} failed: {}", member.getEmail(), e.getMessage());
            }
        }

        // Email — send one email to all recipients
        if (mailProps.isEnabled() && mailSender != null) {
            List<String> emails = recipients.stream()
                    .map(MatterMember::getEmail).filter(e -> e != null && !e.isBlank()).toList();
            if (!emails.isEmpty()) {
                try {
                    String deepLink = mailProps.getAppUrl() + "/matters/" + matter.getId();
                    String html = buildReviewEmailHtml(subject, pipelineName, stage.getName(),
                            docName, matter.getName(), actorName, action, deepLink);
                    jakarta.mail.internet.MimeMessage msg = mailSender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
                    helper.setFrom(mailProps.getFrom());
                    helper.setTo(emails.toArray(new String[0]));
                    helper.setSubject(subject);
                    helper.setText(html, true);
                    mailSender.send(msg);
                    log.info("Review email sent to {} recipients for stage {}", emails.size(), stage.getName());
                } catch (Exception e) {
                    log.error("Review email failed for stage {}: {}", stage.getName(), e.getMessage());
                }
            }
        }
    }

    private List<MatterMember> findMembersForStage(UUID matterId, String requiredRole) {
        if (requiredRole == null || requiredRole.isBlank()) {
            // No role restriction — notify all matter members
            return memberRepo.findByMatterId(matterId);
        }
        // Map pipeline role to matter member roles
        // Pipeline uses: ADMIN, PARTNER, ASSOCIATE, PARALEGAL
        // Matter uses: LEAD_PARTNER, PARTNER, ASSOCIATE, PARALEGAL, CLIENT_CONTACT, EXTERNAL
        List<MatterMemberRole> roles = switch (requiredRole.toUpperCase()) {
            case "PARTNER" -> List.of(MatterMemberRole.LEAD_PARTNER, MatterMemberRole.PARTNER);
            case "ASSOCIATE" -> List.of(MatterMemberRole.ASSOCIATE);
            case "PARALEGAL" -> List.of(MatterMemberRole.PARALEGAL);
            case "ADMIN" -> List.of(MatterMemberRole.LEAD_PARTNER); // admin maps to lead partner on matter
            default -> List.of();
        };
        if (roles.isEmpty()) return memberRepo.findByMatterId(matterId);
        return memberRepo.findByMatterIdAndMatterRoleIn(matterId, roles);
    }

    private String buildReviewMessage(String pipelineName, String stageName, String docName,
                                       String matterName, String actorName, String action) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("*Review %s*\n", action));
        sb.append(String.format("📋 Pipeline: *%s*\n", pipelineName));
        sb.append(String.format("➡️ Now at: *%s*\n", stageName));
        sb.append(String.format("📁 Matter: %s\n", matterName));
        if (docName != null) sb.append(String.format("📄 Document: %s\n", docName));
        sb.append(String.format("👤 By: %s\n", actorName));
        sb.append("\nYour action is needed on this review.");
        return sb.toString();
    }

    private String buildReviewEmailHtml(String subject, String pipelineName, String stageName,
                                         String docName, String matterName, String actorName,
                                         String action, String deepLink) {
        String actionColor = switch (action) {
            case "approved" -> "#22c55e";
            case "returned" -> "#f59e0b";
            default -> "#6366f1";
        };
        String docRow = docName != null
                ? String.format("<tr><td style='padding:6px 0; color:#64748b; font-size:13px'>Document</td><td style='padding:6px 0; font-weight:500'>%s</td></tr>", docName)
                : "";
        return """
            <!DOCTYPE html>
            <html>
            <body style='font-family: system-ui, -apple-system, sans-serif; max-width:600px; margin:0 auto; padding:24px; color:#1e293b; background:#f8fafc'>
              <div style='background:#1e293b; border-radius:12px; padding:24px 28px; margin-bottom:24px'>
                <h2 style='color:#f9fafb; margin:0; font-size:18px'>📋 Review Pipeline</h2>
                <p style='color:#94a3b8; margin:4px 0 0'>Legal Partner</p>
              </div>
              <div style='background:#fff; border:1px solid #e2e8f0; border-radius:12px; padding:24px; margin-bottom:16px'>
                <div style='margin-bottom:16px'>
                  <span style='background:%s20; color:%s; padding:4px 12px; border-radius:12px; font-size:12px; font-weight:600; text-transform:uppercase'>%s</span>
                </div>
                <h3 style='margin:0 0 16px; font-size:18px'>%s</h3>
                <table style='width:100%%; font-size:14px'>
                  <tr><td style='padding:6px 0; color:#64748b; font-size:13px'>Pipeline</td><td style='padding:6px 0; font-weight:500'>%s</td></tr>
                  <tr><td style='padding:6px 0; color:#64748b; font-size:13px'>Current Stage</td><td style='padding:6px 0; font-weight:500'>%s</td></tr>
                  <tr><td style='padding:6px 0; color:#64748b; font-size:13px'>Matter</td><td style='padding:6px 0; font-weight:500'>%s</td></tr>
                  %s
                  <tr><td style='padding:6px 0; color:#64748b; font-size:13px'>Action by</td><td style='padding:6px 0; font-weight:500'>%s</td></tr>
                </table>
              </div>
              <div style='text-align:center; margin-top:24px'>
                <a href='%s' style='background:#6366f1; color:#fff; padding:12px 28px; border-radius:8px; text-decoration:none; font-weight:600; font-size:14px; display:inline-block'>
                  Take Action →
                </a>
              </div>
              <p style='text-align:center; font-size:12px; color:#94a3b8; margin-top:24px'>Legal Partner · Review notification</p>
            </body>
            </html>
            """.formatted(actionColor, actionColor, action, subject, pipelineName, stageName, matterName, docRow, actorName, deepLink);
    }

    // ── DTO mapping ─────────────────────────────────────��──────────────

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
