package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.model.entity.ContractDeadline;
import com.legalpartner.model.entity.DeadlineAlert;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.model.enums.ContractStatus;
import com.legalpartner.repository.ContractDeadlineRepository;
import com.legalpartner.repository.DeadlineAlertRepository;
import com.legalpartner.repository.DocumentMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeadlineSchedulerService {

    private final DeadlineAlertRepository alertRepo;
    private final ContractDeadlineRepository deadlineRepo;
    private final DocumentMetadataRepository documentRepo;
    private final DeadlineService deadlineService;
    private final AuditService auditService;

    /** Run daily at 8am — send alerts, update statuses */
    @Scheduled(cron = "0 0 8 * * *")
    public void dailyDeadlineCheck() {
        LocalDate today = LocalDate.now();

        // 1. Send today's alerts
        List<DeadlineAlert> todaysAlerts = alertRepo.findBySentFalseAndAlertDate(today);
        for (DeadlineAlert alert : todaysAlerts) {
            sendAlertNotification(alert);
            alert.setSent(true);
            alert.setSentAt(Instant.now());
            alertRepo.save(alert);
        }
        if (!todaysAlerts.isEmpty()) {
            log.info("Deadline scheduler: sent {} alert(s)", todaysAlerts.size());
        }

        // 2. Transition ACTIVE → EXPIRING (within 30 days of expiry)
        LocalDate expiringCutoff = today.plusDays(30);
        List<DocumentMetadata> soonExpiring = documentRepo.findActiveExpiringBefore(expiringCutoff);
        for (DocumentMetadata doc : soonExpiring) {
            doc.setContractStatus(ContractStatus.EXPIRING);
            documentRepo.save(doc);
            log.info("Contract {} → EXPIRING (expires {})", doc.getId(), doc.getExpiryDate());
        }

        // 3. Transition EXPIRING → EXPIRED (past expiry date)
        List<DocumentMetadata> expired = documentRepo.findExpiredContracts(today);
        for (DocumentMetadata doc : expired) {
            // Check auto-renewal
            List<ContractDeadline> expiryDeadlines = deadlineRepo.findByDocumentId(doc.getId()).stream()
                    .filter(d -> "EXPIRY".equals(d.getDeadlineType()) && d.isAutoRenewal() && !d.isActioned())
                    .toList();

            if (!expiryDeadlines.isEmpty() && expiryDeadlines.get(0).getRenewalTermMonths() != null) {
                handleAutoRenewal(doc, expiryDeadlines.get(0));
            } else {
                doc.setContractStatus(ContractStatus.EXPIRED);
                documentRepo.save(doc);
                log.info("Contract {} → EXPIRED", doc.getId());
            }
        }
    }

    private void handleAutoRenewal(DocumentMetadata doc, ContractDeadline dl) {
        int months = dl.getRenewalTermMonths();
        LocalDate newExpiry = doc.getExpiryDate().plusMonths(months);
        doc.setExpiryDate(newExpiry);
        doc.setContractStatus(ContractStatus.RENEWED);
        documentRepo.save(doc);

        // Mark old deadline as actioned
        dl.setActioned(true);
        dl.setActionedBy("SYSTEM_SCHEDULER");
        dl.setActionedAt(Instant.now());
        deadlineRepo.save(dl);

        // Create new deadline set for the renewed period
        deadlineService.extractDeadlines(doc.getId(), "SYSTEM_SCHEDULER");

        // Back to ACTIVE after renewal
        doc.setContractStatus(ContractStatus.ACTIVE);
        documentRepo.save(doc);

        log.info("Contract {} auto-renewed to {}", doc.getId(), newExpiry);
    }

    private void sendAlertNotification(DeadlineAlert alert) {
        ContractDeadline dl = alert.getDeadline();
        DocumentMetadata doc = dl.getDocument();
        log.info("DEADLINE ALERT: {} for {} — {} in {} days ({})",
                dl.getDeadlineType(), doc.getFileName(), dl.getDescription(),
                alert.getAlertWindowDays(), dl.getDeadlineDate());

        auditService.publish(AuditEvent.builder()
                .username("SYSTEM_SCHEDULER")
                .action(AuditActionType.DEADLINE_ALERT_SENT)
                .documentId(doc.getId())
                .queryText(dl.getDeadlineType() + " alert — " + alert.getAlertWindowDays() + " days before " + dl.getDeadlineDate())
                .success(true)
                .build());
    }
}
