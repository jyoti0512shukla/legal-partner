package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.config.JwtProperties;
import com.legalpartner.config.LockoutProperties;
import com.legalpartner.config.PasswordPolicyProperties;
import com.legalpartner.model.dto.auth.*;
import com.legalpartner.model.entity.User;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.model.enums.UserRole;
import com.legalpartner.repository.PasswordHistoryRepository;
import com.legalpartner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final PasswordPolicyProperties passwordPolicyProperties;
    private final LockoutProperties lockoutProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final MfaService mfaService;

    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress) {
        if (userRepository.existsByEmail(request.getEmail())) {
            publishAuthEvent(request.getEmail(), "ROLE_ASSOCIATE", AuditActionType.LOGIN_FAILED, false, "Email already registered", ipAddress);
            throw new IllegalArgumentException("Email already registered");
        }

        List<String> errors = passwordPolicyValidator.validate(request.getPassword());
        if (!errors.isEmpty()) {
            publishAuthEvent(request.getEmail(), "ROLE_ASSOCIATE", AuditActionType.LOGIN_FAILED, false, "Password policy: " + String.join("; ", errors), ipAddress);
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        User user = User.builder()
                .email(request.getEmail().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName() != null ? request.getDisplayName().trim() : null)
                .role(UserRole.ASSOCIATE)
                .mustChangePassword(false)
                .build();

        user = userRepository.save(user);
        publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.LOGIN_SUCCESS, true, null, ipAddress);

        String token = jwtService.createToken(user.getEmail(), getRoles(user), false);
        return AuthResponse.builder()
                .token(token)
                .user(UserInfo.from(user))
                .mfaRequired(false)
                .passwordExpired(false)
                .accountLocked(false)
                .build();
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        User user = userRepository.findByEmail(request.getEmail().trim().toLowerCase())
                .orElseGet(() -> {
                    publishAuthEvent(request.getEmail(), "UNKNOWN", AuditActionType.LOGIN_FAILED, false, "Invalid credentials", ipAddress);
                    throw new IllegalArgumentException("Invalid credentials");
                });

        if (!user.isEnabled()) {
            publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.LOGIN_FAILED, false, "Account disabled", ipAddress);
            throw new IllegalArgumentException("Account is disabled");
        }

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.ACCOUNT_LOCKED, false, "Account locked", ipAddress);
            return AuthResponse.builder()
                    .accountLocked(true)
                    .lockedUntil(user.getLockedUntil())
                    .build();
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            user.setFailedLoginCount(user.getFailedLoginCount() + 1);
            if (user.getFailedLoginCount() >= lockoutProperties.getMaxAttempts()) {
                user.setLockedUntil(Instant.now().plusSeconds(lockoutProperties.getLockDurationMinutes() * 60L));
                publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.ACCOUNT_LOCKED, false, "Account locked after failed attempts", ipAddress);
            }
            userRepository.save(user);
            publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.LOGIN_FAILED, false, "Invalid credentials", ipAddress);
            throw new IllegalArgumentException("Invalid credentials");
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        boolean passwordExpired = isPasswordExpired(user);
        if (passwordExpired || user.isMustChangePassword()) {
            String tempToken = jwtService.createTempTokenForPasswordChange(user.getEmail(), getRoles(user));
            publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.LOGIN_SUCCESS, true, "Password change required", ipAddress);
            return AuthResponse.builder()
                    .token(tempToken)
                    .user(UserInfo.from(user))
                    .mfaRequired(user.isMfaEnabled())
                    .passwordExpired(true)
                    .accountLocked(false)
                    .build();
        }

        if (user.isMfaEnabled()) {
            String tempToken = jwtService.createTempTokenForPasswordChange(user.getEmail(), getRoles(user));
            publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.LOGIN_SUCCESS, true, "MFA required", ipAddress);
            return AuthResponse.builder()
                    .token(tempToken)
                    .user(UserInfo.from(user))
                    .mfaRequired(true)
                    .passwordExpired(false)
                    .accountLocked(false)
                    .build();
        }

        publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.LOGIN_SUCCESS, true, null, ipAddress);
        String token = jwtService.createToken(user.getEmail(), getRoles(user), request.isRememberMe());
        return AuthResponse.builder()
                .token(token)
                .user(UserInfo.from(user))
                .mfaRequired(false)
                .passwordExpired(false)
                .accountLocked(false)
                .build();
    }

    public String getEmailFromTempToken(String token) {
        return jwtService.getEmailFromToken(token);
    }

    @Transactional
    public void changePassword(String email, ChangePasswordRequest request, String ipAddress) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.LOGIN_FAILED, false, "Wrong current password", ipAddress);
            throw new IllegalArgumentException("Current password is incorrect");
        }

        List<String> errors = passwordPolicyValidator.validate(request.getNewPassword());
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must be different from current password");
        }

        if (isPasswordInHistory(user, request.getNewPassword())) {
            throw new IllegalArgumentException("Cannot reuse any of your last " + passwordPolicyProperties.getHistoryCount() + " passwords");
        }

        saveToPasswordHistory(user);
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(Instant.now());
        user.setMustChangePassword(false);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.PASSWORD_CHANGED, true, null, ipAddress);
    }

    public AuthResponse validateMfa(String token, String code, String ipAddress) {
        String email = jwtService.getEmailFromToken(token);
        if (email == null) throw new IllegalArgumentException("Invalid or expired token");

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!user.isMfaEnabled()) {
            throw new IllegalArgumentException("MFA not enabled");
        }

        if (!mfaService.verifyCode(user.getId(), code)) {
            publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.LOGIN_FAILED, false, "Invalid MFA code", ipAddress);
            throw new IllegalArgumentException("Invalid MFA code");
        }

        publishAuthEvent(user.getEmail(), "ROLE_" + user.getRole().name(), AuditActionType.LOGIN_SUCCESS, true, null, ipAddress);
        String newToken = jwtService.createToken(user.getEmail(), getRoles(user), false);
        return AuthResponse.builder()
                .token(newToken)
                .user(UserInfo.from(user))
                .mfaRequired(false)
                .passwordExpired(false)
                .accountLocked(false)
                .build();
    }

    private boolean isPasswordExpired(User user) {
        return user.getPasswordChangedAt().plusSeconds(passwordPolicyProperties.getExpiryDays() * 86400L).isBefore(Instant.now());
    }

    private boolean isPasswordInHistory(User user, String newPassword) {
        var history = passwordHistoryRepository.findByUserIdOrderByCreatedAtDesc(
                user.getId(),
                PageRequest.of(0, passwordPolicyProperties.getHistoryCount(), Sort.by("createdAt").descending())
        );
        for (var h : history) {
            if (passwordEncoder.matches(newPassword, h.getPasswordHash())) return true;
        }
        return false;
    }

    private void saveToPasswordHistory(User user) {
        com.legalpartner.model.entity.PasswordHistory ph = com.legalpartner.model.entity.PasswordHistory.builder()
                .userId(user.getId())
                .passwordHash(user.getPasswordHash())
                .build();
        passwordHistoryRepository.save(ph);

        var history = passwordHistoryRepository.findByUserIdOrderByCreatedAtDesc(
                user.getId(),
                PageRequest.of(0, passwordPolicyProperties.getHistoryCount() + 1, Sort.by("createdAt").descending())
        );
        if (history.size() > passwordPolicyProperties.getHistoryCount()) {
            for (int i = passwordPolicyProperties.getHistoryCount(); i < history.size(); i++) {
                passwordHistoryRepository.delete(history.get(i));
            }
        }
    }

    private List<String> getRoles(User user) {
        return List.of("ROLE_" + user.getRole().name());
    }

    private void publishAuthEvent(String username, String role, AuditActionType action, boolean success, String errorMessage, String ipAddress) {
        eventPublisher.publishEvent(AuditEvent.builder()
                .username(username)
                .userRole(role)
                .action(action)
                .endpoint("/api/v1/auth")
                .httpMethod("POST")
                .ipAddress(ipAddress)
                .success(success)
                .errorMessage(errorMessage)
                .build());
    }
}
