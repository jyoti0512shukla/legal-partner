package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.model.entity.*;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.model.enums.NotifyChannel;
import com.legalpartner.repository.ContractDeadlineRepository;
import com.legalpartner.repository.DeadlineAlertConfigRepository;
import com.legalpartner.repository.DeadlineAlertRepository;
import com.legalpartner.repository.DocumentMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeadlineService {

    private final ContractDeadlineRepository deadlineRepo;
    private final DeadlineAlertRepository alertRepo;
    private final DeadlineAlertConfigRepository alertConfigRepo;
    private final DocumentMetadataRepository documentRepo;
    private final AuditService auditService;

    @Transactional
    public List<ContractDeadline> extractDeadlines(UUID documentId, String username) {
        DocumentMetadata doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        // Clear existing deadlines for re-extraction
        deadlineRepo.deleteByDocumentId(documentId);

        List<ContractDeadline> deadlines = new ArrayList<>();

        // Expiry deadline
        if (doc.getExpiryDate() != null) {
            deadlines.add(ContractDeadline.builder()
                    .document(doc)
                    .deadlineType("EXPIRY")
                    .deadlineDate(doc.getExpiryDate())
                    .description("Contract expires")
                    .build());
        }

        // Notice deadline (expiry - notice period)
        if (doc.getExpiryDate() != null && doc.getNoticePeriodDays() != null && doc.getNoticePeriodDays() > 0) {
            LocalDate noticeDate = doc.getExpiryDate().minusDays(doc.getNoticePeriodDays());
            deadlines.add(ContractDeadline.builder()
                    .document(doc)
                    .deadlineType("NOTICE")
                    .deadlineDate(noticeDate)
                    .description("Notice period deadline (" + doc.getNoticePeriodDays() + " days before expiry)")
                    .build());
        }

        // Parse keyTermsJson for additional date fields
        if (doc.getKeyTermsJson() != null) {
            try {
                parseKeyTermsForDeadlines(doc, deadlines);
            } catch (Exception e) {
                log.warn("Failed to parse key terms for deadlines on doc {}: {}", documentId, e.getMessage());
            }
        }

        List<ContractDeadline> saved = deadlineRepo.saveAll(deadlines);

        // Generate alerts for each deadline
        for (ContractDeadline dl : saved) {
            generateAlertsForDeadline(dl);
        }

        auditService.publish(AuditEvent.builder()
                .username(username)
                .action(AuditActionType.DEADLINE_CREATED)
                .documentId(documentId)
                .queryText(saved.size() + " deadlines created")
                .success(true)
                .build());

        log.info("Extracted {} deadlines for document {}", saved.size(), documentId);
        return saved;
    }

    @Transactional
    public void generateAlertsForDeadline(ContractDeadline deadline) {
        // Clear existing alerts for this deadline
        alertRepo.deleteByDeadlineId(deadline.getId());

        List<DeadlineAlertConfig> configs = alertConfigRepo.findByEnabledTrueOrderByAlertWindowDaysDesc();
        LocalDate today = LocalDate.now();

        for (DeadlineAlertConfig config : configs) {
            LocalDate alertDate = deadline.getDeadlineDate().minusDays(config.getAlertWindowDays());
            if (!alertDate.isBefore(today)) {
                alertRepo.save(DeadlineAlert.builder()
                        .deadline(deadline)
                        .alertDate(alertDate)
                        .alertWindowDays(config.getAlertWindowDays())
                        .build());
            }
        }
    }

    @Transactional
    public void regenerateAllAlerts() {
        List<ContractDeadline> all = deadlineRepo.findAll();
        for (ContractDeadline dl : all) {
            if (!dl.isActioned()) {
                generateAlertsForDeadline(dl);
            }
        }
        log.info("Regenerated alerts for {} deadlines", all.size());
    }

    public List<Map<String, Object>> getUpcomingDeadlines(int limit) {
        return deadlineRepo.findActiveUnactioned(PageRequest.of(0, limit)).stream()
                .map(this::toDeadlineDto)
                .toList();
    }

    public List<ContractDeadline> getDeadlinesForDocument(UUID documentId) {
        return deadlineRepo.findByDocumentId(documentId);
    }

    @Transactional
    public ContractDeadline actionDeadline(UUID deadlineId, String username) {
        ContractDeadline dl = deadlineRepo.findById(deadlineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deadline not found"));
        dl.setActioned(true);
        dl.setActionedBy(username);
        dl.setActionedAt(Instant.now());
        ContractDeadline saved = deadlineRepo.save(dl);

        auditService.publish(AuditEvent.builder()
                .username(username)
                .action(AuditActionType.DEADLINE_ACTIONED)
                .documentId(dl.getDocument().getId())
                .queryText("Deadline " + dl.getDeadlineType() + " actioned")
                .success(true)
                .build());

        return saved;
    }

    // ── Alert Config CRUD ──

    public List<DeadlineAlertConfig> getAlertConfig() {
        return alertConfigRepo.findByEnabledTrueOrderByAlertWindowDaysDesc();
    }

    @Transactional
    public DeadlineAlertConfig addAlertConfig(int windowDays, String channel) {
        DeadlineAlertConfig config = DeadlineAlertConfig.builder()
                .alertWindowDays(windowDays)
                .notifyChannel(NotifyChannel.valueOf(channel))
                .build();
        return alertConfigRepo.save(config);
    }

    @Transactional
    public DeadlineAlertConfig updateAlertConfig(UUID configId, Map<String, Object> body) {
        DeadlineAlertConfig config = alertConfigRepo.findById(configId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found"));
        if (body.containsKey("alertWindowDays")) config.setAlertWindowDays((Integer) body.get("alertWindowDays"));
        if (body.containsKey("notifyChannel")) config.setNotifyChannel(NotifyChannel.valueOf((String) body.get("notifyChannel")));
        if (body.containsKey("enabled")) config.setEnabled((Boolean) body.get("enabled"));
        return alertConfigRepo.save(config);
    }

    @Transactional
    public void removeAlertConfig(UUID configId) {
        alertConfigRepo.deleteById(configId);
    }

    // ── Helpers ──

    private Map<String, Object> toDeadlineDto(ContractDeadline dl) {
        DocumentMetadata doc = dl.getDocument();
        long daysUntil = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), dl.getDeadlineDate());
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", dl.getId());
        dto.put("documentId", doc.getId());
        dto.put("documentName", doc.getFileName());
        dto.put("deadlineType", dl.getDeadlineType());
        dto.put("deadlineDate", dl.getDeadlineDate().toString());
        dto.put("description", dl.getDescription());
        dto.put("daysUntil", daysUntil);
        dto.put("isAutoRenewal", dl.isAutoRenewal());
        dto.put("actioned", dl.isActioned());
        dto.put("partyA", doc.getPartyA());
        dto.put("partyB", doc.getPartyB());
        dto.put("contractStatus", doc.getContractStatus() != null ? doc.getContractStatus().name() : null);
        return dto;
    }

    private void parseKeyTermsForDeadlines(DocumentMetadata doc, List<ContractDeadline> deadlines) {
        // Parse keyTermsJson to look for auto_renewal, renewal_term, payment dates
        // The keyTermsJson follows the extraction pipeline format with entries[]
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(doc.getKeyTermsJson());
            var entries = root.path("entries");
            if (!entries.isArray()) return;

            for (var entry : entries) {
                String field = entry.path("canonicalField").asText("");
                String value = entry.path("value").asText("");

                if ("auto_renewal".equals(field) && value.toLowerCase().contains("yes")) {
                    // Mark the expiry deadline as auto-renewal
                    for (ContractDeadline dl : deadlines) {
                        if ("EXPIRY".equals(dl.getDeadlineType())) {
                            dl.setAutoRenewal(true);
                        }
                    }
                }

                if ("renewal_term".equals(field)) {
                    // Try to parse months from value like "12 months" or "1 year"
                    try {
                        int months = parseMonths(value);
                        for (ContractDeadline dl : deadlines) {
                            if ("EXPIRY".equals(dl.getDeadlineType())) {
                                dl.setRenewalTermMonths(months);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse key terms for additional deadlines: {}", e.getMessage());
        }
    }

    private int parseMonths(String value) {
        String lower = value.toLowerCase().trim();
        if (lower.contains("year")) {
            int years = Integer.parseInt(lower.replaceAll("[^0-9]", ""));
            return years * 12;
        }
        return Integer.parseInt(lower.replaceAll("[^0-9]", ""));
    }
}
