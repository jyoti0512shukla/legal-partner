package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.model.dto.TeamCreateRequest;
import com.legalpartner.model.dto.TeamDto;
import com.legalpartner.model.dto.TeamMemberDto;
import com.legalpartner.model.entity.Team;
import com.legalpartner.model.entity.TeamMember;
import com.legalpartner.model.entity.User;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.repository.TeamMemberRepository;
import com.legalpartner.repository.TeamRepository;
import com.legalpartner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TeamService {

    private final TeamRepository teamRepo;
    private final TeamMemberRepository memberRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    public List<TeamDto> listTeams() {
        return teamRepo.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    public TeamDto getTeam(UUID id) {
        return toDto(teamRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found")));
    }

    public TeamDto createTeam(TeamCreateRequest req, String username) {
        UUID userId = userRepo.findByEmail(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
        Team team = Team.builder().name(req.name()).description(req.description()).createdBy(userId).build();
        team = teamRepo.save(team);
        auditService.publish(AuditEvent.builder().username(username)
                .action(AuditActionType.MATTER_CREATED).queryText("Team: " + req.name()).success(true).build());
        return toDto(team);
    }

    public TeamDto updateTeam(UUID id, TeamCreateRequest req) {
        Team team = teamRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        team.setName(req.name());
        team.setDescription(req.description());
        return toDto(teamRepo.save(team));
    }

    @Transactional
    public void deleteTeam(UUID id) {
        teamRepo.deleteById(id);
    }

    public List<TeamMemberDto> listMembers(UUID teamId) {
        return memberRepo.findByTeamId(teamId).stream().map(this::toMemberDto).toList();
    }

    @Transactional
    public TeamMemberDto addMember(UUID teamId, UUID userId, String actingUser) {
        Team team = teamRepo.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (memberRepo.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already in team");
        }
        TeamMember member = TeamMember.builder().team(team).user(user).build();
        member = memberRepo.save(member);
        log.info("Added {} to team {} by {}", user.getEmail(), team.getName(), actingUser);
        return toMemberDto(member);
    }

    @Transactional
    public void removeMember(UUID teamId, UUID userId, String actingUser) {
        memberRepo.deleteByTeamIdAndUserId(teamId, userId);
        log.info("Removed user {} from team {} by {}", userId, teamId, actingUser);
    }

    public List<UUID> getUserIdsInTeam(UUID teamId) {
        return memberRepo.findUserIdsByTeamId(teamId);
    }

    private TeamDto toDto(Team t) {
        int count = memberRepo.findByTeamId(t.getId()).size();
        return new TeamDto(t.getId(), t.getName(), t.getDescription(), count, t.getCreatedAt());
    }

    private TeamMemberDto toMemberDto(TeamMember m) {
        User u = m.getUser();
        return new TeamMemberDto(m.getId(), u.getId(), u.getEmail(), u.getDisplayName(),
                u.getRole().name(), m.getAddedAt());
    }
}
