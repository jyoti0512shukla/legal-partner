package com.legalpartner.service;

import com.legalpartner.integration.SlackWebhookProvider;
import com.legalpartner.model.entity.AgentConfig;
import com.legalpartner.model.entity.Matter;
import com.legalpartner.model.entity.MatterFinding;
import com.legalpartner.model.enums.FindingSeverity;
import com.legalpartner.model.enums.NotifyChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        if (channel == NotifyChannel.NONE || channel == NotifyChannel.IN_APP) return;

        String message = buildMessage(findings, matter);
        log.info("Agent notification ({}) for matter {}: {}", channel, matter.getName(), message);

        // For now, log the notification. Full Slack/Email/Teams dispatch can reuse
        // the existing integration connection lookup pattern from ConnectorService.
    }

    private String buildMessage(List<MatterFinding> findings, Matter matter) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Deal Intelligence: %d findings on %s\n", findings.size(), matter.getName()));
        for (MatterFinding f : findings) {
            sb.append(String.format("  %s %s: %s\n",
                    f.getSeverity() == FindingSeverity.HIGH ? "🔴" : "🟡",
                    f.getClauseType(), f.getTitle()));
        }
        return sb.toString();
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
