package com.legalpartner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.MailProperties;
import com.legalpartner.model.dto.WorkflowConnector;
import com.legalpartner.model.entity.WorkflowRun;
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
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ConnectorService {

    @Nullable
    private final JavaMailSender mailSender;
    private final MailProperties mailProps;
    private final ObjectMapper objectMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    public ConnectorService(@Nullable JavaMailSender mailSender,
                            MailProperties mailProps,
                            ObjectMapper objectMapper) {
        this.mailSender = mailSender;
        this.mailProps = mailProps;
        this.objectMapper = objectMapper;
    }

    @Async
    public void fireAll(WorkflowRun run, List<WorkflowConnector> connectors,
                        Map<String, Object> results, String workflowName) {
        if (connectors == null || connectors.isEmpty()) return;
        for (WorkflowConnector connector : connectors) {
            try {
                switch (connector.getType()) {
                    case WEBHOOK -> fireWebhook(connector, run, results, workflowName);
                    case EMAIL   -> fireEmail(connector, run, results, workflowName);
                }
            } catch (Exception e) {
                log.error("Connector {} failed for run {}: {}", connector.getType(), run.getId(), e.getMessage());
            }
        }
    }

    // ── Webhook ───────────────────────────────────────────────────────────────

    private void fireWebhook(WorkflowConnector connector, WorkflowRun run,
                             Map<String, Object> results, String workflowName) throws Exception {
        String url = connector.getConfig().get("url");
        if (url == null || url.isBlank()) {
            log.warn("Webhook connector has no URL for run {}", run.getId());
            return;
        }

        Map<String, Object> payload = Map.of(
                "event", "workflow.completed",
                "runId", run.getId().toString(),
                "workflowName", workflowName,
                "documentId", run.getDocumentId().toString(),
                "username", run.getUsername(),
                "status", run.getStatus().name(),
                "startedAt", run.getStartedAt().toString(),
                "completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : Instant.now().toString(),
                "results", results
        );

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
                """.formatted(workflowName, run.getDocumentId(), duration, steps, deepLink);
    }
}
