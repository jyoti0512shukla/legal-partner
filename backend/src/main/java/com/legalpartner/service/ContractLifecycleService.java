package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.config.ContractLifecycleConfig;
import com.legalpartner.event.DocumentExecutedEvent;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.DocumentVersion;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.model.enums.ContractStatus;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.DocumentVersionRepository;
import com.legalpartner.service.learning.LearningConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractLifecycleService {

    private final DocumentMetadataRepository documentRepo;
    private final DocumentVersionRepository versionRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final ContractLifecycleConfig lifecycle;
    private final LearningConfig learningConfig;

    @Transactional
    public DocumentMetadata transitionStatus(UUID documentId, ContractStatus newStatus, String username) {
        DocumentMetadata doc = findOrThrow(documentId);
        ContractStatus current = doc.getContractStatus();

        if (current == newStatus) return doc;

        if (current != null && !isTransitionAllowed(current, newStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid transition: " + current + " → " + newStatus);
        }

        // Lock / unlock per contract_lifecycle.yml
        if (lifecycle.locks(newStatus)) doc.setLocked(true);
        if (lifecycle.unlocks(newStatus)) doc.setLocked(false);

        doc.setContractStatus(newStatus);
        DocumentMetadata saved = documentRepo.save(doc);

        auditService.publish(AuditEvent.builder()
                .username(username)
                .action(AuditActionType.CONTRACT_STATUS_CHANGED)
                .documentId(documentId)
                .queryText((current != null ? current.name() : "null") + " → " + newStatus.name())
                .success(true)
                .build());

        log.info("Contract {} transitioned: {} → {} by {}", documentId, current, newStatus, username);

        if (newStatus == ContractStatus.EXECUTED) {
            eventPublisher.publishEvent(new DocumentExecutedEvent(documentId, username));
        }

        return saved;
    }

    @Transactional
    public DocumentMetadata initializeLifecycle(UUID documentId, String username) {
        DocumentMetadata doc = findOrThrow(documentId);

        if (doc.getContractStatus() != null) {
            return doc; // already initialized
        }

        doc.setContractStatus(lifecycle.initial());
        doc.setCurrentVersion(1);

        // Create v1 from existing stored file if not already versioned
        if (versionRepo.countByDocumentId(documentId) == 0 && doc.getStoredPath() != null) {
            DocumentVersion v1 = DocumentVersion.builder()
                    .document(doc)
                    .versionNumber(1)
                    .storedPath(doc.getStoredPath())
                    .fileSize(doc.getFileSizeBytes())
                    .source(learningConfig.isAiGenerated(doc.getSource()) ? "AI_GENERATED" : "UPLOAD")
                    .changeSummary("Initial version")
                    .createdBy(username)
                    .build();
            versionRepo.save(v1);
        }

        DocumentMetadata saved = documentRepo.save(doc);

        auditService.publish(AuditEvent.builder()
                .username(username)
                .action(AuditActionType.CONTRACT_STATUS_CHANGED)
                .documentId(documentId)
                .queryText("null → " + lifecycle.initial().name())
                .success(true)
                .build());

        return saved;
    }

    @Transactional
    public DocumentMetadata finalize(UUID documentId, String userBrief, String userKeyPointsJson, String username) {
        DocumentMetadata doc = findOrThrow(documentId);

        // Allowed pre-signature statuses per contract_lifecycle.yml finalize.allowed_from
        ContractStatus current = doc.getContractStatus();
        if (!lifecycle.canFinalizeFrom(current)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot finalize document in status: " + current);
        }

        doc.setUserBrief(userBrief);
        doc.setUserKeyPoints(userKeyPointsJson);
        doc.setFinalizedAt(Instant.now());
        doc.setFinalizedBy(username);
        doc.setLocked(true);
        doc.setContractStatus(lifecycle.finalizeTo());

        DocumentMetadata saved = documentRepo.save(doc);

        auditService.publish(AuditEvent.builder()
                .username(username)
                .action(AuditActionType.DOCUMENT_FINALIZED)
                .documentId(documentId)
                .queryText("Finalized with brief, locked for signature")
                .success(true)
                .build());

        log.info("Document {} finalized by {}", documentId, username);
        return saved;
    }

    @Transactional
    public DocumentMetadata markExecuted(UUID documentId, String username) {
        DocumentMetadata doc = findOrThrow(documentId);

        // Allow marking as executed from various statuses (pre-signed uploads may skip steps)
        doc.setLocked(true);
        doc.setContractStatus(ContractStatus.EXECUTED);

        DocumentMetadata saved = documentRepo.save(doc);

        auditService.publish(AuditEvent.builder()
                .username(username)
                .action(AuditActionType.DOCUMENT_EXECUTED)
                .documentId(documentId)
                .queryText("Manually marked as executed")
                .success(true)
                .build());

        eventPublisher.publishEvent(new DocumentExecutedEvent(documentId, username));
        log.info("Document {} marked executed by {}", documentId, username);
        return saved;
    }

    public boolean isTransitionAllowed(ContractStatus from, ContractStatus to) {
        return lifecycle.nextStatuses(from).contains(to);
    }

    public Set<ContractStatus> getAllowedNextStatuses(ContractStatus current) {
        return lifecycle.nextStatuses(current);
    }

    public void assertNotLocked(DocumentMetadata doc) {
        if (doc.isLocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document is locked for signature — no further edits allowed");
        }
    }

    private DocumentMetadata findOrThrow(UUID id) {
        return documentRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + id));
    }
}
