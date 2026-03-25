package com.legalpartner.controller;

import com.legalpartner.model.dto.TeamCreateRequest;
import com.legalpartner.model.dto.TeamDto;
import com.legalpartner.model.dto.TeamMemberDto;
import com.legalpartner.service.TeamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @GetMapping
    public List<TeamDto> list() {
        return teamService.listTeams();
    }

    @GetMapping("/{id}")
    public TeamDto get(@PathVariable UUID id) {
        return teamService.getTeam(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public TeamDto create(@Valid @RequestBody TeamCreateRequest req, Authentication auth) {
        return teamService.createTeam(req, auth.getName());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public TeamDto update(@PathVariable UUID id, @Valid @RequestBody TeamCreateRequest req) {
        return teamService.updateTeam(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public void delete(@PathVariable UUID id) {
        teamService.deleteTeam(id);
    }

    @GetMapping("/{id}/members")
    public List<TeamMemberDto> listMembers(@PathVariable UUID id) {
        return teamService.listMembers(id);
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public TeamMemberDto addMember(@PathVariable UUID id, @RequestParam UUID userId, Authentication auth) {
        return teamService.addMember(id, userId, auth.getName());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public void removeMember(@PathVariable UUID id, @PathVariable UUID userId, Authentication auth) {
        teamService.removeMember(id, userId, auth.getName());
    }
}
