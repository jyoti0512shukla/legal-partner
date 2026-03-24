package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.MailProperties;
import com.legalpartner.integration.SlackWebhookProvider;
import com.legalpartner.model.dto.WorkflowConnector;
import com.legalpartner.model.entity.IntegrationConnection;
import com.legalpartner.model.entity.WorkflowRun;
import com.legalpartner.repository.IntegrationConnectionRepository;
import com.legalpartner.repository.MatterMemberRepository;
import com.legalpartner.repository.UserRepository;
import com.legalpartner.repository.WorkflowRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ConnectorService {

    @Nullable
    private final JavaMailSender mailSender;
    private final MailProperties mailProps;
    private final ObjectMapper objectMapper;
    private final WorkflowRunRepository runRepo;
    private final SlackWebhookProvider slackProvider;
    private final IntegrationConnectionRepository integrationRepo;
    private final UserRepository userRepo;
    private final MatterMemberRepository matterMemberRepo;

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    public ConnectorService(@Nullable JavaMailSender mailSender,
                            MailProperties mailProps,
                            ObjectMapper objectMapper,
                            WorkflowRunRepository runRepo,
                            SlackWebhookProvider slackProvider,
                            IntegrationConnectionRepository integrationRepo,
                            UserRepository userRepo,
                            MatterMemberRepository matterMemberRepo) {
        this.mailSender = mailSender;
        this.mailProps = mailProps;
        this.objectMapper = objectMapper;
        this.runRepo = runRepo;
        this.slackProvider = slackProvider;
        this.integrationRepo = integrationRepo;
        this.userRepo = userRepo;
        this.matterMemberRepo = matterMemberRepo;
    }

    @Async
    public void fireAll(WorkflowRun run, List<WorkflowConnector> connectors,
                        Map<String, Object> results, String workflowName) {
        if (connectors == null || connectors.isEmpty()) return;

        List<Map<String, Object>> logs = new ArrayList<>();

        for (WorkflowConnector connector : connectors) {
            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("type", connector.getType().name());
            logEntry.put("firedAt", Instant.now().toString());

            try {
                Map<String, Object> details = switch (connector.getType()) {
                    case WEBHOOK -> { fireWebhook(connector, run, results, workflowName); yield Map.of("url", connector.getConfig().getOrDefault("url", "")); }
                    case EMAIL   -> fireEmailWithDetails(connector, run, results, workflowName);
                    case SLACK   -> { fireSlack(run, workflowName); yield Map.of("channel", "slack"); }
                };
                logEntry.put("status", "SUCCESS");
                logEntry.putAll(details);
            } catch (Exception e) {
                log.error("Connector {} failed for run {}: {}", connector.getType(), run.getId(), e.getMessage());
                logEntry.put("status", "FAILED");
                logEntry.put("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
            }

            logs.add(logEntry);
        }

        // Persist connector logs back to the run
        runRepo.findById(run.getId()).ifPresent(r -> {
            try {
                // Merge with any existing logs (e.g. from a prior connector fire attempt)
                List<Map<String, Object>> existing = new ArrayList<>();
                if (r.getConnectorLogs() != null && !r.getConnectorLogs().isBlank()) {
                    existing = objectMapper.readValue(r.getConnectorLogs(), new TypeReference<>() {});
                }
                existing.addAll(logs);
                r.setConnectorLogs(objectMapper.writeValueAsString(existing));
                runRepo.save(r);
            } catch (Exception ignored) {}
        });
    }

    // ── Webhook ───────────────────────────────────────────────────────────────

    private void fireWebhook(WorkflowConnector connector, WorkflowRun run,
                             Map<String, Object> results, String workflowName) throws Exception {
        String url = connector.getConfig().get("url");
        if (url == null || url.isBlank()) {
            log.warn("Webhook connector has no URL for run {}", run.getId());
            return;
        }

        String docIdStr = run.getDocumentId() != null ? run.getDocumentId().toString() : null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "workflow.completed");
        payload.put("runId", run.getId().toString());
        payload.put("workflowName", workflowName);
        if (docIdStr != null) payload.put("documentId", docIdStr);
        payload.put("username", run.getUsername());
        payload.put("status", run.getStatus().name());
        payload.put("startedAt", run.getStartedAt().toString());
        payload.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : Instant.now().toString());
        payload.put("results", results);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String secret = connector.getConfig().get("secret");
        if (secret != null && !secret.isBlank()) {
            headers.set("X-LegalPartner-Secret", secret);
        }

        String body = objectMapper.writeValueAsString(payload);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        log.info("Webhook fired for run {} → {} (HTTP {})", run.getId(), url, response.getStatusCode().value());
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    private void fireEmail(WorkflowConnector connector, WorkflowRun run,
                           Map<String, Object> results, String workflowName) throws Exception {
        if (!mailProps.isEnabled() || mailSender == null) {
            log.info("Mail not enabled — skipping email connector for run {}", run.getId());
            return;
        }

        String recipientsStr = connector.getConfig().get("recipients");
        if (recipientsStr == null || recipientsStr.isBlank()) {
            log.warn("Email connector has no recipients for run {}", run.getId());
            return;
        }

        String[] recipients = recipientsStr.split("[,;\\s]+");

        // If the run has a matter, validate recipients are matter members
        if (run.getMatter() != null) {
            java.util.Set<String> memberEmails = matterMemberRepo.findByMatterId(run.getMatter().getId())
                    .stream()
                    .map(m -> m.getEmail().toLowerCase())
                    .collect(java.util.stream.Collectors.toSet());
            List<String> validRecipients = new ArrayList<>();
            for (String r : recipients) {
                if (memberEmails.contains(r.trim().toLowerCase())) {
                    validRecipients.add(r.trim());
                } else {
                    log.warn("Email recipient {} is not a member of matter {} — skipping",
                            r.trim(), run.getMatter().getId());
                }
            }
            if (validRecipients.isEmpty()) {
                log.warn("No valid recipients after matter membership filter for run {}", run.getId());
                return;
            }
            recipients = validRecipients.toArray(new String[0]);
        }

        String subject = connector.getConfig().getOrDefault("subject",
                "Legal Partner — " + workflowName + " completed");

        String html = buildEmailHtml(run, results, workflowName);

        jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(mailProps.getFrom());
        helper.setTo(recipients);
        helper.setSubject(subject);
        helper.setText(html, true);
        mailSender.send(message);

        log.info("Email sent for run {} to {} recipient(s)", run.getId(), recipients.length);
    }

    private Map<String, Object> fireEmailWithDetails(WorkflowConnector connector, WorkflowRun run,
                                                      Map<String, Object> results, String workflowName) throws Exception {
        String recipientsStr = connector.getConfig().getOrDefault("recipients", "");
        fireEmail(connector, run, results, workflowName);
        String[] sent = recipientsStr.split("[,;\\s]+");
        return Map.of(
                "recipients", List.of(sent),
                "subject", connector.getConfig().getOrDefault("subject",
                        "Legal Partner — " + workflowName + " completed"),
                "from", mailProps.getFrom()
        );
    }

    private String buildEmailHtml(WorkflowRun run, Map<String, Object> results, String workflowName) {
        long durationMs = run.getCompletedAt() != null
                ? run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli() : 0;
        String duration = durationMs < 60000
                ? Math.round(durationMs / 1000.0) + "s"
                : Math.round(durationMs / 60000.0) + "m " + Math.round((durationMs % 60000) / 1000.0) + "s";

        StringBuilder steps = new StringBuilder();
        results.forEach((stepType, result) -> {
            String label = stepType.replace("_", " ").toLowerCase().trim();
            if (!label.isEmpty()) label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
            steps.append("<li style='margin-bottom:6px'>").append(label).append(" — completed</li>");
        });
        if (steps.isEmpty()) steps.append("<li>No steps recorded</li>");

        String deepLink = mailProps.getAppUrl() + "/workflows/run/" + run.getId();
        String docInfo = run.getDocumentId() != null ? run.getDocumentId().toString() : "Draft run (no document)";

        return """
                <!DOCTYPE html>
                <html>
                <body style='font-family: system-ui, -apple-system, sans-serif; max-width:600px; margin:0 auto; padding:24px; color:#1e293b; background:#f8fafc'>
                  <div style='background:#1e293b; border-radius:12px; padding:24px 28px; margin-bottom:24px'>
                    <h2 style='color:#f9fafb; margin:0; font-size:18px'>⚡ Workflow Completed</h2>
                    <p style='color:#94a3b8; margin:4px 0 0'>Legal Partner</p>
                  </div>

                  <div style='background:#fff; border:1px solid #e2e8f0; border-radius:12px; padding:24px; margin-bottom:16px'>
                    <h3 style='margin:0 0 16px; font-size:20px'>%s</h3>
                    <table style='width:100%%; border-collapse:collapse; font-size:14px'>
                      <tr><td style='padding:6px 0; color:#64748b'>Document</td><td style='padding:6px 0; font-weight:500'>%s</td></tr>
                      <tr><td style='padding:6px 0; color:#64748b'>Duration</td><td style='padding:6px 0; font-weight:500'>%s</td></tr>
                      <tr><td style='padding:6px 0; color:#64748b'>Status</td><td style='padding:6px 0'><span style='background:#dcfce7; color:#16a34a; padding:2px 10px; border-radius:20px; font-size:12px; font-weight:600'>COMPLETED</span></td></tr>
                    </table>
                  </div>

                  <div style='background:#fff; border:1px solid #e2e8f0; border-radius:12px; padding:24px; margin-bottom:16px'>
                    <h4 style='margin:0 0 12px; font-size:14px; color:#64748b; text-transform:uppercase; letter-spacing:.05em'>Steps Completed</h4>
                    <ul style='margin:0; padding-left:20px; font-size:14px; line-height:1.6'>%s</ul>
                  </div>

                  <div style='text-align:center; margin-top:24px'>
                    <a href='%s' style='background:#6366f1; color:#fff; padding:12px 28px; border-radius:8px; text-decoration:none; font-weight:600; font-size:14px; display:inline-block'>
                      View Full Results →
                    </a>
                  </div>

                  <p style='text-align:center; font-size:12px; color:#94a3b8; margin-top:24px'>Legal Partner · Automated notification</p>
                </body>
                </html>
                """.formatted(workflowName, docInfo, duration, steps, deepLink);
    }

    // ── Slack ──────────────────────────────────────────────────────────────────

    private void fireSlack(WorkflowRun run, String workflowName) {
        // Look up the user's Slack webhook from their integration connection
        var user = userRepo.findByEmail(run.getUsername());
        if (user.isEmpty()) {
            log.warn("Slack connector: user not found for run {}", run.getId());
            return;
        }
        var conn = integrationRepo.findByUserIdAndProvider(user.get().getId(), "SLACK");
        if (conn.isEmpty()) {
            log.warn("Slack not configured for user {} — skipping", run.getUsername());
            return;
        }

        String webhookUrl = null;
        try {
            var config = objectMapper.readTree(conn.get().getConfig());
            webhookUrl = config.has("webhookUrl") ? config.get("webhookUrl").asText() : null;
        } catch (Exception ignored) {}

        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Slack webhook URL not found for user {}", run.getUsername());
            return;
        }

        String status = run.getStatus() != null ? run.getStatus().name() : "COMPLETED";
        String message = String.format("*%s* — %s\nRun: `%s`\nUser: %s",
                workflowName, status, run.getId(), run.getUsername());

        slackProvider.sendNotification(webhookUrl, message);
        log.info("Slack notification sent for run {}", run.getId());
    }
}
