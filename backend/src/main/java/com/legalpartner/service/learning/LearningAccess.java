package com.legalpartner.service.learning;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Role checks for learning-loop actions, from learning.yml {@code access}. Used in @PreAuthorize. */
@Component("learningAccess")
@RequiredArgsConstructor
public class LearningAccess {

    private final LearningConfig config;

    public boolean canCurate(Authentication auth) {
        return hasAny(auth, config.getCurateRoles());
    }

    public boolean canDispute(Authentication auth) {
        return hasAny(auth, config.getDisputeRoles());
    }

    static boolean hasAny(Authentication auth, Set<String> roles) {
        if (auth == null || roles.isEmpty()) return false;
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", "").toUpperCase())
                .anyMatch(roles::contains);
    }
}
