package com.legalpartner.repository;

import com.legalpartner.model.entity.MatterMember;
import com.legalpartner.model.enums.MatterMemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MatterMemberRepository extends JpaRepository<MatterMember, UUID> {

    List<MatterMember> findByMatterId(UUID matterId);

    List<MatterMember> findByUserId(UUID userId);

    Optional<MatterMember> findByMatterIdAndUserId(UUID matterId, UUID userId);

    boolean existsByMatterIdAndUserId(UUID matterId, UUID userId);

    void deleteByMatterIdAndUserId(UUID matterId, UUID userId);

    @Query("SELECT mm.matter.id FROM MatterMember mm WHERE mm.user.id = :userId")
    List<UUID> findMatterIdsByUserId(@Param("userId") UUID userId);

    List<MatterMember> findByMatterIdAndMatterRoleIn(UUID matterId, List<MatterMemberRole> roles);
}
