package com.legalpartner.service;

import com.legalpartner.model.entity.User;
import com.legalpartner.model.enums.MatterMemberRole;
import com.legalpartner.model.enums.UserRole;
import com.legalpartner.repository.MatterMemberRepository;
import com.legalpartner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MatterAccessService {

    private final MatterMemberRepository memberRepo;
    private final UserRepository userRepo;

    public boolean isMember(UUID matterId, UUID userId) {
        return memberRepo.existsByMatterIdAndUserId(matterId, userId);
    }

    public Optional<MatterMemberRole> getUserRoleOnMatter(UUID matterId, UUID userId) {
        return memberRepo.findByMatterIdAndUserId(matterId, userId)
                .map(m -> m.getMatterRole());
    }

    public List<UUID> getAccessibleMatterIds(UUID userId) {
        return memberRepo.findMatterIdsByUserId(userId);
    }

    public boolean canManageTeam(UUID matterId, UUID userId, UserRole systemRole) {
        if (systemRole == UserRole.ADMIN) return true;
        return getUserRoleOnMatter(matterId, userId)
                .map(r -> r == MatterMemberRole.LEAD_PARTNER)
                .orElse(false);
    }

    public void requireMembership(UUID matterId, UUID userId, UserRole systemRole) {
        if (systemRole == UserRole.ADMIN) return;
        if (!isMember(matterId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this matter");
        }
    }

    public User resolveUser(Authentication auth) {
        return userRepo.findByEmail(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
