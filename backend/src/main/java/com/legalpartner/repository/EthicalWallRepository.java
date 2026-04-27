package com.legalpartner.repository;

import com.legalpartner.model.entity.EthicalWall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface EthicalWallRepository extends JpaRepository<EthicalWall, UUID> {

    /** Get all active walls involving a specific matter (as either side) */
    @Query("SELECT e FROM EthicalWall e WHERE e.active = true AND (e.matterAId = :matterId OR e.matterBId = :matterId)")
    List<EthicalWall> findActiveWallsForMatter(UUID matterId);

    /** Get all matter IDs that are walled off from a specific matter */
    @Query("""
        SELECT CASE WHEN e.matterAId = :matterId THEN e.matterBId ELSE e.matterAId END
        FROM EthicalWall e
        WHERE e.active = true AND (e.matterAId = :matterId OR e.matterBId = :matterId)
        """)
    Set<UUID> findBlockedMatterIds(UUID matterId);

    boolean existsByMatterAIdAndMatterBIdAndActiveTrue(UUID matterAId, UUID matterBId);
}
