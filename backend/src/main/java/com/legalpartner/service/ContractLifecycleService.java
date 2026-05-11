package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.event.DocumentExecutedEvent;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.DocumentVersion;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.model.enums.ContractStatus;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
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

    private static final Map<ContractStatus, Set<ContractStatus>> ALLOWED_TRANSITIONS = Map.ofEntries(
            Map.entry(ContractStatus.DRAFT, Set.of(
                    ContractStatus.INTERNAL_REVIEW, ContractStatus.NEGOTIATING, ContractStatus.PENDING_SIGNATURE)),
            Map.entry(ContractStatus.INTERNAL_REVIEW, Set.of(
                    ContractStatus.APPROVED, ContractStatus.DRAFT)),
            Map.entry(ContractStatus.APPROVED, Set.of(
                    ContractStatus.NEGOTIATING, ContractStatus.PENDING_SIGNATURE)),
            Map.entry(ContractStatus.NEGOTIATING, Set.of(
                    ContractStatus.INTERNAL_REVIEW, ContractStatus.APPROVED, ContractStatus.PENDING_SIGNATURE)),
            Map.entry(ContractStatus.PENDING_SIGNATURE, Set.of(
                    ContractStatus.EXECUTED, ContractStatus.NEGOTIATING)),
            Map.entry(ContractStatus.EXECUTED, Set.of(
                    ContractStatus.ACTIVE)),
            Map.entry(ContractStatus.ACTIVE, Set.of(
                    ContractStatus.EXPIRING, ContractStatus.TERMINATED, ContractStatus.RENEWED)),
            Map.entry(ContractStatus.EXPIRING, Set.of(
                    ContractStatus.EXPIRED, ContractStatus.RENEWED, ContractStatus.TERMINATED))
    );

    @Transactional
    public DocumentMetadata transitionStatus(UUID documentId, ContractStatus newStatus, String username) {
        DocumentMetadata doc = findOrThrow(documentId);
        ContractStatus current = doc.getContractStatus();

        if (current == newStatus) return doc;

        if (current != null && !isTransitionAllowed(current, newStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid transition: " + current + " → " + newStatus);
        }

        // Lock on PENDING_SIGNATURE
        if (newStatus == ContractStatus.PENDING_SIGNATURE) {
            doc.setLocked(true);
        }

        // Unlock if bouncing back from PENDING_SIGNATURE
        if (current == ContractStatus.PENDING_SIGNATURE && newStatus == ContractStatus.NEGOTIATING) {
            doc.setLocked(false);
        }

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

        doc.setContractStatus(ContractStatus.DRAFT);
        doc.setCurrentVersion(1);

        // Create v1 from existing stored file if not already versioned
        if (versionRepo.countByDocumentId(documentId) == 0 && doc.getStoredPath() != null) {
            DocumentVersion v1 = DocumentVersion.builder()
                    .document(doc)
                    .versionNumber(1)
                    .storedPath(doc.getStoredPath())
                    .fileSize(doc.getFileSizeBytes())
                    .source("DRAFT_ASYNC".equals(doc.getSource()) ? "AI_GENERATED" : "UPLOAD")
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
                .queryText("null → DRAFT")
                .success(true)
                .build());

        return saved;
    }

    @Transactional
    public DocumentMetadata finalize(UUID documentId, String userBrief, String userKeyPointsJson, String username) {
        DocumentMetadata doc = findOrThrow(documentId);

        // Allow finalization from various pre-signature statuses
        ContractStatus current = doc.getContractStatus();
        if (current != null && current != ContractStatus.DRAFT && current != ContractStatus.APPROVED
                && current != ContractStatus.NEGOTIATING && current != ContractStatus.INTERNAL_REVIEW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot finalize document in status: " + current);
        }

        doc.setUserBrief(userBrief);
        doc.setUserKeyPoints(userKeyPointsJson);
        doc.setFinalizedAt(Instant.now());
        doc.setFinalizedBy(username);
        doc.setLocked(true);
        doc.setContractStatus(ContractStatus.PENDING_SIGNATURE);

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
        if (from == null) return to == ContractStatus.DRAFT;
        Set<ContractStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public Set<ContractStatus> getAllowedNextStatuses(ContractStatus current) {
        if (current == null) return Set.of(ContractStatus.DRAFT);
        return ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
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
