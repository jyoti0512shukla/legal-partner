package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.model.entity.*;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.model.enums.FindingType;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.MatterFindingRepository;
import com.legalpartner.repository.MatterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatterAgentService {

    private final AgentConfigService configService;
    private final PlaybookComparisonService playbookComparison;
    private final CrossDocConflictDetector conflictDetector;
    private final MatterFindingRepository findingRepo;
    private final MatterRepository matterRepo;
    private final DocumentMetadataRepository docRepo;
    private final AuditService auditService;
    private final AgentNotificationService notificationService;

    @Async
    public void analyzeDocument(UUID matterId, UUID documentId, String username) {
        try {
            AgentConfig config = configService.getConfig();
            Matter matter = matterRepo.findById(matterId).orElse(null);
            if (matter == null) return;
            if (!"ACTIVE".equals(matter.getStatus() != null ? matter.getStatus().name() : "")) return;

            DocumentMetadata doc = docRepo.findById(documentId).orElse(null);
            if (doc == null) return;

            log.info("Agent: analyzing document {} in matter {}", doc.getFileName(), matter.getName());

            auditService.publish(AuditEvent.builder()
                    .username(username).action(AuditActionType.AGENT_ANALYSIS_TRIGGERED)
                    .endpoint("matter/" + matterId + "/doc/" + documentId)
                    .documentId(documentId).success(true).build());

            List<MatterFinding> allFindings = new ArrayList<>();

            // Playbook comparison
            if (config.isCheckPlaybook() && matter.getDefaultPlaybook() != null) {
                List<MatterFinding> playbookFindings = playbookComparison.compareClauses(matter, doc);
                allFindings.addAll(playbookFindings);
                log.info("Agent: {} playbook findings for {}", playbookFindings.size(), doc.getFileName());
            }

            // Cross-document conflict detection
            if (config.isCrossReferenceDocs()) {
                List<MatterFinding> conflictFindings = conflictDetector.detectConflicts(matter, doc);
                allFindings.addAll(conflictFindings);
                log.info("Agent: {} cross-doc conflicts for {}", conflictFindings.size(), doc.getFileName());
            }

            if (!allFindings.isEmpty()) {
                findingRepo.saveAll(allFindings);
            }

            // Notify if needed
            notificationService.notifyIfNeeded(allFindings, config, matter);

            auditService.publish(AuditEvent.builder()
                    .username(username).action(AuditActionType.AGENT_ANALYSIS_COMPLETED)
                    .endpoint("matter/" + matterId + "/doc/" + documentId)
                    .queryText(allFindings.size() + " findings")
                    .documentId(documentId).success(true).build());

            log.info("Agent: completed analysis for {} — {} total findings", doc.getFileName(), allFindings.size());

        } catch (Exception e) {
            log.error("Agent analysis failed for doc {} in matter {}: {}", documentId, matterId, e.getMessage(), e);
        }
    }

    @Async
    @Transactional
    public void reanalyzeAllDocuments(UUID matterId, String username) {
        try {
            // Delete existing playbook findings
            findingRepo.deleteByMatterIdAndFindingTypeIn(matterId,
                    List.of(FindingType.PLAYBOOK_DEVIATION, FindingType.PLAYBOOK_NON_NEGOTIABLE));

            // Re-analyze each document
            List<DocumentMetadata> docs = docRepo.findAllByMatterId(matterId);
            for (DocumentMetadata doc : docs) {
                analyzeDocument(matterId, doc.getId(), username);
            }
        } catch (Exception e) {
            log.error("Agent reanalysis failed for matter {}: {}", matterId, e.getMessage(), e);
        }
    }
}
