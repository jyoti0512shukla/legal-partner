package com.legalpartner.service;

import com.legalpartner.model.entity.EthicalWall;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.EthicalWallRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Enforces ethical walls at the retrieval layer.
 * Given a matter, returns the set of document IDs that MUST be excluded from RAG results.
 * Also logs what was excluded and why for audit trail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EthicalWallService {

    private final EthicalWallRepository wallRepo;
    private final DocumentMetadataRepository docRepo;

    /**
     * Get all document IDs that must be excluded when querying for a specific matter.
     * Returns empty set if no walls exist or matterId is null.
     */
    public Set<String> getBlockedDocumentIds(UUID matterId) {
        if (matterId == null) return Set.of();

        Set<UUID> blockedMatterIds = wallRepo.findBlockedMatterIds(matterId);
        if (blockedMatterIds.isEmpty()) return Set.of();

        // Get all document IDs belonging to blocked matters
        Set<String> blockedDocIds = new HashSet<>();
        for (UUID blockedMatterId : blockedMatterIds) {
            List<String> docIds = docRepo.findIdStringsByMatterUuid(blockedMatterId);
            blockedDocIds.addAll(docIds);
        }

        log.info("Ethical wall: matter {} has {} walls → {} blocked documents",
                matterId, blockedMatterIds.size(), blockedDocIds.size());
        return blockedDocIds;
    }

    /**
     * Get wall details for audit logging.
     * Returns list of "Matter X blocked due to wall with Matter Y (reason)"
     */
    public List<String> getWallAuditDetails(UUID matterId) {
        if (matterId == null) return List.of();
        List<EthicalWall> walls = wallRepo.findActiveWallsForMatter(matterId);
        return walls.stream()
                .map(w -> {
                    UUID otherMatter = w.getMatterAId().equals(matterId) ? w.getMatterBId() : w.getMatterAId();
                    return "Wall: matter " + otherMatter + " blocked (" + w.getReason() + ")";
                })
                .toList();
    }

    /** Create a new ethical wall between two matters */
    public EthicalWall createWall(UUID matterAId, UUID matterBId, String reason, UUID createdBy) {
        // Ensure consistent ordering (smaller UUID first)
        UUID first = matterAId.compareTo(matterBId) < 0 ? matterAId : matterBId;
        UUID second = matterAId.compareTo(matterBId) < 0 ? matterBId : matterAId;

        if (wallRepo.existsByMatterAIdAndMatterBIdAndActiveTrue(first, second)) {
            throw new IllegalStateException("Wall already exists between these matters");
        }

        EthicalWall wall = EthicalWall.builder()
                .matterAId(first)
                .matterBId(second)
                .reason(reason)
                .createdBy(createdBy)
                .build();
        wall = wallRepo.save(wall);
        log.info("Created ethical wall: {} ↔ {} (reason: {})", first, second, reason);
        return wall;
    }

    /** Deactivate a wall (soft delete) */
    public void deactivateWall(UUID wallId) {
        wallRepo.findById(wallId).ifPresent(w -> {
            w.setActive(false);
            wallRepo.save(w);
            log.info("Deactivated ethical wall: {} ↔ {}", w.getMatterAId(), w.getMatterBId());
        });
    }
}
