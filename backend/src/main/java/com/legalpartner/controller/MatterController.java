package com.legalpartner.controller;

import com.legalpartner.model.dto.matter.MatterMemberRequest;
import com.legalpartner.model.dto.matter.MatterMemberResponse;
import com.legalpartner.model.dto.matter.MatterRequest;
import com.legalpartner.model.dto.matter.MatterResponse;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.User;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.service.MatterAccessService;
import com.legalpartner.service.MatterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/matters")
@RequiredArgsConstructor
public class MatterController {

    private final MatterService matterService;
    private final MatterAccessService matterAccessService;
    private final DocumentMetadataRepository documentMetadataRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public MatterResponse create(@Valid @RequestBody MatterRequest request, Authentication auth) {
        return matterService.createMatter(request, auth.getName());
    }

    @GetMapping
    public List<MatterResponse> list(Authentication auth) {
        User user = matterAccessService.resolveUser(auth);
        return matterService.listMatters(user.getId(), user.getRole());
    }

    @GetMapping("/active")
    public List<MatterResponse> listActive(Authentication auth) {
        User user = matterAccessService.resolveUser(auth);
        return matterService.listActiveMatters(user.getId(), user.getRole());
    }

    @GetMapping("/{id}")
    public MatterResponse get(@PathVariable UUID id, Authentication auth) {
        User user = matterAccessService.resolveUser(auth);
        matterAccessService.requireMembership(id, user.getId(), user.getRole());
        return matterService.getMatter(id);
    }

    @GetMapping("/{id}/documents")
    public Page<DocumentMetadata> getDocuments(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return documentMetadataRepository.findByMatter_Id(
                id, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "uploadedAt")));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public MatterResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody MatterRequest request,
                                  Authentication auth) {
        User user = matterAccessService.resolveUser(auth);
        matterAccessService.requireMembership(id, user.getId(), user.getRole());
        return matterService.updateMatter(id, request, auth.getName());
    }

    @PatchMapping("/{id}/status")
    public MatterResponse updateStatus(
            @PathVariable UUID id,
            @RequestParam String status,
            Authentication auth) {
        return matterService.updateStatus(id, status, auth.getName());
    }

    // ── Team management endpoints ────────────────────────────────────────────

    @GetMapping("/{id}/team")
    public List<MatterMemberResponse> listTeam(@PathVariable UUID id, Authentication auth) {
        User user = matterAccessService.resolveUser(auth);
        matterAccessService.requireMembership(id, user.getId(), user.getRole());
        return matterService.listMembers(id);
    }

    @PostMapping("/{id}/team")
    @ResponseStatus(HttpStatus.CREATED)
    public MatterMemberResponse addTeamMember(
            @PathVariable UUID id,
            @Valid @RequestBody MatterMemberRequest request,
            Authentication auth) {
        User user = matterAccessService.resolveUser(auth);
        return matterService.addMember(id, request, user.getId(), user.getRole());
    }

    @PostMapping("/{id}/team/add-team")
    public List<MatterMemberResponse> addTeamToMatter(
            @PathVariable UUID id,
            @RequestParam UUID teamId,
            @RequestParam(defaultValue = "ASSOCIATE") String matterRole,
            Authentication auth) {
        User user = matterAccessService.resolveUser(auth);
        return matterService.addTeamToMatter(id, teamId, matterRole, user.getId(), user.getRole());
    }

    @DeleteMapping("/{id}/team/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTeamMember(
            @PathVariable UUID id,
            @PathVariable UUID memberId,
            Authentication auth) {
        User user = matterAccessService.resolveUser(auth);
        matterService.removeMember(id, memberId, user.getId(), user.getRole());
    }
}
