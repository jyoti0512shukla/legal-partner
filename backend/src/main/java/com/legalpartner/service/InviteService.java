package com.legalpartner.service;

import com.legalpartner.audit.AuditEvent;
import com.legalpartner.config.MailProperties;
import com.legalpartner.model.dto.auth.AuthConfigDto;
import com.legalpartner.model.entity.AuthConfigEntity;
import com.legalpartner.model.entity.AuthToken;
import com.legalpartner.model.entity.User;
import com.legalpartner.model.enums.AuditActionType;
import com.legalpartner.model.enums.UserRole;
import com.legalpartner.repository.AuthConfigRepository;
import com.legalpartner.repository.AuthTokenRepository;
import com.legalpartner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InviteService {

    private final AuthTokenRepository tokenRepo;
    private final AuthConfigRepository configRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final MailProperties mailProps;
    @Nullable private final JavaMailSender mailSender;

    // ── Config ────────────────────────────────────────────────────────────

    public AuthConfigEntity getConfig() {
        return configRepo.findAll().stream().findFirst()
                .orElseGet(() -> configRepo.save(AuthConfigEntity.builder().build()));
    }

    public AuthConfigEntity updateConfig(AuthConfigDto dto) {
        AuthConfigEntity c = getConfig();
        c.setInviteExpiryHours(dto.inviteExpiryHours());
        c.setInviteResendCooldownMin(dto.inviteResendCooldownMin());
        c.setPasswordResetExpiryHours(dto.passwordResetExpiryHours());
        c.setMaxPasswordResetsPerHour(dto.maxPasswordResetsPerHour());
        c.setMaxFailedLogins(dto.maxFailedLogins());
        c.setLockoutDurationMinutes(dto.lockoutDurationMinutes());
        return configRepo.save(c);
    }

    public AuthConfigDto toDto(AuthConfigEntity c) {
        return new AuthConfigDto(c.getInviteExpiryHours(), c.getInviteResendCooldownMin(),
                c.getPasswordResetExpiryHours(), c.getMaxPasswordResetsPerHour(),
                c.getMaxFailedLogins(), c.getLockoutDurationMinutes());
    }

    // ── Invite ────────────────────────────────────────────────────────────

    public User createInvitedUser(String email, UserRole role, String invitedBy) {
        if (userRepo.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists: " + email);
        }
        User user = User.builder()
                .email(email)
                .passwordHash("INVITED_NO_PASSWORD")
                .role(role)
                .enabled(false)
                .accountStatus("INVITED")
                .build();
        user = userRepo.save(user);
        log.info("Created invited user: {} (role: {}) by {}", email, role, invitedBy);
        return user;
    }

    public String generateInviteToken(User user) {
        AuthConfigEntity config = getConfig();

        // Check cooldown
        tokenRepo.findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(user.getId(), "INVITE")
                .ifPresent(lastToken -> {
                    Instant cooldownEnd = lastToken.getCreatedAt().plus(config.getInviteResendCooldownMin(), ChronoUnit.MINUTES);
                    if (Instant.now().isBefore(cooldownEnd)) {
                        long minutesLeft = Instant.now().until(cooldownEnd, ChronoUnit.MINUTES) + 1;
                        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "Invite was sent recently. Try again in " + minutesLeft + " minutes.");
                    }
                });

        String token = UUID.randomUUID().toString();
        tokenRepo.save(AuthToken.builder()
                .user(user).token(token).tokenType("INVITE")
                .expiresAt(Instant.now().plus(config.getInviteExpiryHours(), ChronoUnit.HOURS))
                .build());
        return token;
    }

    public void sendInviteEmail(User user, String token, String matterName) {
        if (mailSender == null || !mailProps.isEnabled()) {
            log.info("Mail not enabled — invite token for {}: {}", user.getEmail(), token);
            return;
        }
        try {
            String link = mailProps.getAppUrl() + "/invite/" + token;
            String html = buildInviteHtml(user.getEmail(), matterName, link);
            jakarta.mail.internet.MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(mailProps.getFrom());
            helper.setTo(user.getEmail());
            helper.setSubject("You've been invited to Legal Partner");
            helper.setText(html, true);
            mailSender.send(msg);
            log.info("Invite email sent to {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send invite email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    public User acceptInvite(String token, String password, String displayName) {
        AuthToken authToken = tokenRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid invite token"));
        if (authToken.isExpired()) throw new ResponseStatusException(HttpStatus.GONE, "Invite expired");
        if (authToken.isUsed()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Invite already used");

        User user = authToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        user.setAccountStatus("ACTIVE");
        if (displayName != null && !displayName.isBlank()) user.setDisplayName(displayName);
        user.setPasswordChangedAt(Instant.now());
        userRepo.save(user);

        authToken.setUsedAt(Instant.now());
        tokenRepo.save(authToken);

        log.info("User {} accepted invite", user.getEmail());
        return user;
    }

    // ── Forgot Password ───────────────────────────────────────────────────

    public void requestPasswordReset(String email) {
        User user = userRepo.findByEmail(email).orElse(null);
        if (user == null) {
            log.info("Password reset requested for unknown email: {}", email);
            return; // Don't reveal if email exists
        }

        AuthConfigEntity config = getConfig();

        // Rate limit
        long recentResets = tokenRepo.countByUserIdAndTokenTypeAndCreatedAtAfter(
                user.getId(), "PASSWORD_RESET", Instant.now().minus(1, ChronoUnit.HOURS));
        if (recentResets >= config.getMaxPasswordResetsPerHour()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many password reset requests. Try again later.");
        }

        String token = UUID.randomUUID().toString();
        tokenRepo.save(AuthToken.builder()
                .user(user).token(token).tokenType("PASSWORD_RESET")
                .expiresAt(Instant.now().plus(config.getPasswordResetExpiryHours(), ChronoUnit.HOURS))
                .build());

        sendResetEmail(user, token);

        auditService.publish(AuditEvent.builder()
                .username(email).action(AuditActionType.PASSWORD_RESET_REQUESTED)
                .success(true).build());
    }

    public void resetPassword(String token, String newPassword) {
        AuthToken authToken = tokenRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reset token"));
        if (authToken.isExpired()) throw new ResponseStatusException(HttpStatus.GONE, "Reset link expired");
        if (authToken.isUsed()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Reset link already used");

        User user = authToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepo.save(user);

        authToken.setUsedAt(Instant.now());
        tokenRepo.save(authToken);

        auditService.publish(AuditEvent.builder()
                .username(user.getEmail()).action(AuditActionType.PASSWORD_CHANGED)
                .success(true).build());
        log.info("Password reset for {}", user.getEmail());
    }

    // ── User Admin ────────────────────────────────────────────────────────

    public void resendInvite(UUID userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String token = generateInviteToken(user);
        sendInviteEmail(user, token, null);
    }

    // ── Email templates ───────────────────────────────────────────────────

    private void sendResetEmail(User user, String token) {
        if (mailSender == null || !mailProps.isEnabled()) {
            log.info("Mail not enabled — reset token for {}: {}", user.getEmail(), token);
            return;
        }
        try {
            String link = mailProps.getAppUrl() + "/reset-password/" + token;
            String html = """
                <div style="font-family:system-ui;max-width:500px;margin:0 auto;padding:24px">
                  <h2>Reset your password</h2>
                  <p>Click the link below to set a new password. This link expires in %d hours.</p>
                  <a href="%s" style="display:inline-block;background:#6366f1;color:#fff;padding:12px 24px;border-radius:8px;text-decoration:none;margin:16px 0">
                    Reset Password
                  </a>
                  <p style="color:#64748b;font-size:12px">If you didn't request this, ignore this email.</p>
                </div>
                """.formatted(getConfig().getPasswordResetExpiryHours(), link);
            jakarta.mail.internet.MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(mailProps.getFrom());
            helper.setTo(user.getEmail());
            helper.setSubject("Legal Partner — Reset your password");
            helper.setText(html, true);
            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Failed to send reset email: {}", e.getMessage());
        }
    }

    private String buildInviteHtml(String email, String matterName, String link) {
        String matterLine = matterName != null ? "<p>You've been added to matter: <strong>" + matterName + "</strong></p>" : "";
        return """
            <div style="font-family:system-ui;max-width:500px;margin:0 auto;padding:24px">
              <div style="background:#1e293b;border-radius:12px;padding:20px;margin-bottom:20px">
                <h2 style="color:#f9fafb;margin:0">Welcome to Legal Partner</h2>
              </div>
              <p>You've been invited to join Legal Partner as a team member.</p>
              %s
              <p>Click below to set your password and activate your account:</p>
              <a href="%s" style="display:inline-block;background:#6366f1;color:#fff;padding:12px 24px;border-radius:8px;text-decoration:none;margin:16px 0">
                Set Up Your Account
              </a>
              <p style="color:#64748b;font-size:12px">This link expires in %d hours.</p>
            </div>
            """.formatted(matterLine, link, getConfig().getInviteExpiryHours());
    }
}
