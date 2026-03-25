package com.legalpartner.repository;

import com.legalpartner.model.entity.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {
    List<TeamMember> findByTeamId(UUID teamId);
    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);
    void deleteByTeamIdAndUserId(UUID teamId, UUID userId);
    @Query("SELECT tm.user.id FROM TeamMember tm WHERE tm.team.id = :teamId")
    List<UUID> findUserIdsByTeamId(@Param("teamId") UUID teamId);
}
