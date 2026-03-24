package com.legalpartner.service;

import com.legalpartner.model.dto.matter.MatterMemberRequest;
import com.legalpartner.model.dto.matter.MatterMemberResponse;
import com.legalpartner.model.dto.matter.MatterRequest;
import com.legalpartner.model.dto.matter.MatterResponse;
import com.legalpartner.model.entity.Matter;
import com.legalpartner.model.entity.MatterMember;
import com.legalpartner.model.entity.User;
import com.legalpartner.model.enums.MatterMemberRole;
import com.legalpartner.model.enums.MatterStatus;
import com.legalpartner.model.enums.PracticeArea;
import com.legalpartner.model.enums.UserRole;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.MatterMemberRepository;
import com.legalpartner.repository.MatterRepository;
import com.legalpartner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatterService {

    private final MatterRepository matterRepository;
    private final DocumentMetadataRepository documentRepository;
    private final MatterMemberRepository matterMemberRepository;
    private final MatterAccessService matterAccessService;
    private final UserRepository userRepository;

    public MatterResponse createMatter(MatterRequest request, String username) {
        if (matterRepository.findByMatterRef(request.matterRef()).isPresent()) {
            throw new IllegalArgumentException("Matter reference already exists: " + request.matterRef());
        }
        Matter matter = Matter.builder()
                .name(request.name())
                .matterRef(request.matterRef())
                .clientName(request.clientName())
                .practiceArea(parsePracticeArea(request.practiceArea()))
                .description(request.description())
                .createdBy(username)
                .build();
        matter = matterRepository.save(matter);
        log.info("Matter created: {} by {}", matter.getMatterRef(), username);

        // Auto-add creator as LEAD_PARTNER
        User creator = userRepository.findByEmail(username).orElse(null);
        if (creator != null) {
            MatterMember leadMember = MatterMember.builder()
                    .matter(matter)
                    .user(creator)
                    .email(creator.getEmail())
                    .matterRole(MatterMemberRole.LEAD_PARTNER)
                    .addedBy(creator)
                    .build();
            matterMemberRepository.save(leadMember);
            log.info("Auto-added {} as LEAD_PARTNER on matter {}", username, matter.getMatterRef());
        }

        return MatterResponse.from(matter, 0);
    }

    public List<MatterResponse> listMatters(UUID userId, UserRole systemRole) {
        if (systemRole == UserRole.ADMIN) {
            return matterRepository.findAllByOrderByCreatedAtDesc().stream()
                    .map(m -> MatterResponse.from(m, documentRepository.countByMatterUuid(m.getId())))
                    .toList();
        }
        List<UUID> accessibleIds = matterMemberRepository.findMatterIdsByUserId(userId);
        if (accessibleIds.isEmpty()) return List.of();
        return matterRepository.findAllById(accessibleIds).stream()
                .map(m -> MatterResponse.from(m, documentRepository.countByMatterUuid(m.getId())))
                .toList();
    }

    public List<MatterResponse> listActiveMatters(UUID userId, UserRole systemRole) {
        if (systemRole == UserRole.ADMIN) {
            return matterRepository.findByStatusOrderByCreatedAtDesc(MatterStatus.ACTIVE).stream()
                    .map(m -> MatterResponse.from(m, documentRepository.countByMatterUuid(m.getId())))
                    .toList();
        }
        List<UUID> accessibleIds = matterMemberRepository.findMatterIdsByUserId(userId);
        if (accessibleIds.isEmpty()) return List.of();
        return matterRepository.findByStatusOrderByCreatedAtDesc(MatterStatus.ACTIVE).stream()
                .filter(m -> accessibleIds.contains(m.getId()))
                .map(m -> MatterResponse.from(m, documentRepository.countByMatterUuid(m.getId())))
                .toList();
    }

    public MatterResponse getMatter(UUID id) {
        Matter matter = matterRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Matter not found: " + id));
        return MatterResponse.from(matter, documentRepository.countByMatterUuid(id));
    }

    public MatterResponse updateStatus(UUID id, String status, String username) {
        Matter matter = matterRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Matter not found: " + id));
        matter.setStatus(MatterStatus.valueOf(status.toUpperCase()));
        matter = matterRepository.save(matter);
        log.info("Matter {} status updated to {} by {}", matter.getMatterRef(), status, username);
        return MatterResponse.from(matter, documentRepository.countByMatterUuid(id));
    }

    public Matter requireMatter(UUID matterId) {
        return matterRepository.findById(matterId)
                .orElseThrow(() -> new NoSuchElementException("Matter not found: " + matterId));
    }

    // ── Team management ──────────────────────────────────────────────────────

    public MatterMemberResponse addMember(UUID matterId, MatterMemberRequest req,
                                          UUID actingUserId, UserRole actingRole) {
        if (!matterAccessService.canManageTeam(matterId, actingUserId, actingRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only LEAD_PARTNER or ADMIN can manage team");
        }
        Matter matter = requireMatter(matterId);
        User actingUser = userRepository.findById(actingUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Acting user not found"));

        MatterMemberRole role;
        try {
            role = MatterMemberRole.valueOf(req.matterRole().toUpperCase());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid matter role: " + req.matterRole());
        }

        MatterMember.MatterMemberBuilder builder = MatterMember.builder()
                .matter(matter)
                .email(req.email())
                .matterRole(role)
                .addedBy(actingUser);

        // If userId provided, link the internal user
        if (req.userId() != null) {
            User targetUser = userRepository.findById(req.userId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + req.userId()));
            if (matterMemberRepository.existsByMatterIdAndUserId(matterId, targetUser.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member of this matter");
            }
            builder.user(targetUser);
        }

        MatterMember saved = matterMemberRepository.save(builder.build());
        log.info("Added member {} ({}) to matter {} by {}", req.email(), role, matterId, actingUserId);
        return toResponse(saved);
    }

    public void removeMember(UUID matterId, UUID targetUserId, UUID actingUserId, UserRole actingRole) {
        if (!matterAccessService.canManageTeam(matterId, actingUserId, actingRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only LEAD_PARTNER or ADMIN can manage team");
        }
        MatterMember member = matterMemberRepository.findByMatterIdAndUserId(matterId, targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found on this matter"));
        matterMemberRepository.delete(member);
        log.info("Removed member {} from matter {} by {}", targetUserId, matterId, actingUserId);
    }

    public List<MatterMemberResponse> listMembers(UUID matterId) {
        return matterMemberRepository.findByMatterId(matterId).stream()
                .map(this::toResponse)
                .toList();
    }

    private MatterMemberResponse toResponse(MatterMember m) {
        String displayName = m.getUser() != null ? m.getUser().getDisplayName() : null;
        UUID userId = m.getUser() != null ? m.getUser().getId() : null;
        return new MatterMemberResponse(
                m.getId(),
                userId,
                m.getEmail(),
                displayName,
                m.getMatterRole().name(),
                m.getCreatedAt()
        );
    }

    private PracticeArea parsePracticeArea(String s) {
        if (s == null || s.isBlank()) return null;
        try { return PracticeArea.valueOf(s.toUpperCase()); } catch (Exception e) { return null; }
    }
}
