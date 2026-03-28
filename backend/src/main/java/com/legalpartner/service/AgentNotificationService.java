package com.legalpartner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.MailProperties;
import com.legalpartner.integration.MicrosoftTeamsProvider;
import com.legalpartner.integration.SlackWebhookProvider;
import com.legalpartner.model.entity.AgentConfig;
import com.legalpartner.model.entity.Matter;
import com.legalpartner.model.entity.MatterFinding;
import com.legalpartner.model.enums.FindingSeverity;
import com.legalpartner.model.enums.NotifyChannel;
import com.legalpartner.repository.IntegrationConnectionRepository;
import com.legalpartner.repository.MatterMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentNotificationService {

    private final SlackWebhookProvider slackProvider;
    private final MicrosoftTeamsProvider teamsProvider;
    private final IntegrationConnectionRepository integrationRepo;
    private final MatterMemberRepository matterMemberRepo;
    private final MailProperties mailProps;
    @Nullable
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;

    public void notifyIfNeeded(List<MatterFinding> findings, AgentConfig config, Matter matter) {
        if (findings.isEmpty()) return;
        if (isQuietHours(config)) return;

        Map<FindingSeverity, List<MatterFinding>> bySeverity = findings.stream()
                .collect(Collectors.groupingBy(MatterFinding::getSeverity));

        if (bySeverity.containsKey(FindingSeverity.HIGH)) {
            dispatch(config.getNotifyHigh(), bySeverity.get(FindingSeverity.HIGH), matter);
        }
        if (bySeverity.containsKey(FindingSeverity.MEDIUM)) {
            dispatch(config.getNotifyMedium(), bySeverity.get(FindingSeverity.MEDIUM), matter);
        }
    }

    private void dispatch(NotifyChannel channel, List<MatterFinding> findings, Matter matter) {
        if (channel == null || channel == NotifyChannel.NONE || channel == NotifyChannel.IN_APP) return;

        String message = buildMessage(findings, matter);
        String title = "AI Agent: " + findings.size() + " findings on " + matter.getName();
        log.info("Agent notification ({}) for matter {}: {} findings", channel, matter.getName(), findings.size());

        try {
            switch (channel) {
                case SLACK -> sendSlackToMatterMembers(matter, message);
                case TEAMS -> sendTeamsToMatterMembers(matter, title, message);
                case EMAIL -> sendEmailToMatterMembers(matter, title, findings);
                default -> {}
            }
        } catch (Exception e) {
            log.error("Agent notification dispatch failed ({}) for matter {}: {}", channel, matter.getName(), e.getMessage());
        }
    }

    private void sendSlackToMatterMembers(Matter matter, String message) {
        // Send to all matter members who have Slack configured
        matterMemberRepo.findByMatterId(matter.getId()).forEach(member -> {
            try {
                var conn = integrationRepo.findByUserIdAndProvider(member.getUser().getId(), "SLACK");
                if (conn.isEmpty()) return;
                var config = objectMapper.readTree(conn.get().getConfig());
                String webhookUrl = config.has("webhookUrl") ? config.get("webhookUrl").asText() : null;
                if (webhookUrl != null && !webhookUrl.isBlank()) {
                    slackProvider.sendNotification(webhookUrl, message);
                    log.info("Agent Slack notification sent to {}", member.getEmail());
                }
            } catch (Exception e) {
                log.warn("Agent Slack to {} failed: {}", member.getEmail(), e.getMessage());
            }
        });
    }

    private void sendTeamsToMatterMembers(Matter matter, String title, String message) {
        matterMemberRepo.findByMatterId(matter.getId()).forEach(member -> {
            try {
                var conn = integrationRepo.findByUserIdAndProvider(member.getUser().getId(), "MICROSOFT_TEAMS");
                if (conn.isEmpty()) return;
                var config = objectMapper.readTree(conn.get().getConfig());
                String webhookUrl = config.has("webhookUrl") ? config.get("webhookUrl").asText() : null;
                if (webhookUrl != null && !webhookUrl.isBlank()) {
                    teamsProvider.sendNotification(webhookUrl, title, message.replace("\n", "<br>"));
                    log.info("Agent Teams notification sent to {}", member.getEmail());
                }
            } catch (Exception e) {
                log.warn("Agent Teams to {} failed: {}", member.getEmail(), e.getMessage());
            }
        });
    }

    private void sendEmailToMatterMembers(Matter matter, String title, List<MatterFinding> findings) {
        if (!mailProps.isEnabled() || mailSender == null) {
            log.info("Mail not enabled — skipping agent email notification for matter {}", matter.getName());
            return;
        }

        List<String> emails = matterMemberRepo.findByMatterId(matter.getId()).stream()
                .map(m -> m.getEmail())
                .filter(e -> e != null && !e.isBlank())
                .toList();

        if (emails.isEmpty()) return;

        try {
            String html = buildEmailHtml(matter, title, findings);
            jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailProps.getFrom());
            helper.setTo(emails.toArray(new String[0]));
            helper.setSubject(title);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Agent email sent to {} recipients for matter {}", emails.size(), matter.getName());
        } catch (Exception e) {
            log.error("Agent email failed for matter {}: {}", matter.getName(), e.getMessage());
        }
    }

    private String buildMessage(List<MatterFinding> findings, Matter matter) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("*AI Agent: %d findings on %s*\n", findings.size(), matter.getName()));
        for (MatterFinding f : findings) {
            sb.append(String.format("  %s *%s*: %s\n",
                    f.getSeverity() == FindingSeverity.HIGH ? "🔴" : "🟡",
                    f.getClauseType(), f.getTitle()));
            if (f.getDescription() != null) {
                sb.append(String.format("    _%s_\n", f.getDescription()));
            }
        }
        return sb.toString();
    }

    private String buildEmailHtml(Matter matter, String title, List<MatterFinding> findings) {
        StringBuilder rows = new StringBuilder();
        for (MatterFinding f : findings) {
            String color = f.getSeverity() == FindingSeverity.HIGH ? "#ef4444" : "#f59e0b";
            String badge = f.getSeverity() == FindingSeverity.HIGH ? "HIGH" : "MEDIUM";
            rows.append(String.format("""
                <tr>
                  <td style='padding:8px 12px; border-bottom:1px solid #e2e8f0'>
                    <span style='background:%s20; color:%s; padding:2px 8px; border-radius:12px; font-size:11px; font-weight:600'>%s</span>
                  </td>
                  <td style='padding:8px 12px; border-bottom:1px solid #e2e8f0; font-weight:500'>%s</td>
                  <td style='padding:8px 12px; border-bottom:1px solid #e2e8f0; color:#64748b; font-size:13px'>%s</td>
                </tr>
                """, color, color, badge,
                    f.getClauseType() != null ? f.getClauseType() : "—",
                    f.getDescription() != null ? f.getDescription() : "—"));
        }

        String deepLink = mailProps.getAppUrl() + "/matters/" + matter.getId();

        return """
            <!DOCTYPE html>
            <html>
            <body style='font-family: system-ui, -apple-system, sans-serif; max-width:600px; margin:0 auto; padding:24px; color:#1e293b; background:#f8fafc'>
              <div style='background:#1e293b; border-radius:12px; padding:24px 28px; margin-bottom:24px'>
                <h2 style='color:#f9fafb; margin:0; font-size:18px'>🤖 AI Agent Alert</h2>
                <p style='color:#94a3b8; margin:4px 0 0'>Legal Partner</p>
              </div>
              <div style='background:#fff; border:1px solid #e2e8f0; border-radius:12px; padding:24px; margin-bottom:16px'>
                <h3 style='margin:0 0 4px; font-size:18px'>%s</h3>
                <p style='color:#64748b; margin:0 0 16px; font-size:14px'>Matter: %s</p>
                <table style='width:100%%; border-collapse:collapse; font-size:14px'>
                  <thead>
                    <tr style='text-align:left; color:#64748b; font-size:12px; text-transform:uppercase; letter-spacing:.05em'>
                      <th style='padding:8px 12px; border-bottom:2px solid #e2e8f0'>Severity</th>
                      <th style='padding:8px 12px; border-bottom:2px solid #e2e8f0'>Clause</th>
                      <th style='padding:8px 12px; border-bottom:2px solid #e2e8f0'>Finding</th>
                    </tr>
                  </thead>
                  <tbody>%s</tbody>
                </table>
              </div>
              <div style='text-align:center; margin-top:24px'>
                <a href='%s' style='background:#6366f1; color:#fff; padding:12px 28px; border-radius:8px; text-decoration:none; font-weight:600; font-size:14px; display:inline-block'>
                  View Matter →
                </a>
              </div>
              <p style='text-align:center; font-size:12px; color:#94a3b8; margin-top:24px'>Legal Partner · AI Agent notification</p>
            </body>
            </html>
            """.formatted(title, matter.getName(), rows, deepLink);
    }

    private boolean isQuietHours(AgentConfig config) {
        if (config.getQuietHoursStart() == null || config.getQuietHoursEnd() == null) return false;
        LocalTime now = LocalTime.now();
        LocalTime start = config.getQuietHoursStart();
        LocalTime end = config.getQuietHoursEnd();
        if (start.isBefore(end)) {
            return now.isAfter(start) && now.isBefore(end);
        } else {
            return now.isAfter(start) || now.isBefore(end);
        }
    }
}
