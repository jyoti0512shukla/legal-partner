package com.legalpartner.service;

import com.legalpartner.config.MailProperties;
import com.legalpartner.model.dto.auth.MfaSetupResponse;
import com.legalpartner.model.entity.MfaBackupCode;
import com.legalpartner.model.entity.TrustedDevice;
import com.legalpartner.model.entity.User;
import com.legalpartner.model.entity.UserMfaSecret;
import com.legalpartner.repository.MfaBackupCodeRepository;
import com.legalpartner.repository.TrustedDeviceRepository;
import com.legalpartner.repository.UserMfaSecretRepository;
import com.legalpartner.repository.UserRepository;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comprehensive MFA service — TOTP + Email OTP + backup codes + trusted devices.
 */
@Service
@Slf4j
public class MfaService {

    private static final String ISSUER = "ContractIQ";
    private static final int BACKUP_CODE_COUNT = 10;
    private static final int EMAIL_OTP_LENGTH = 6;
    private static final int EMAIL_OTP_EXPIRY_MINUTES = 5;

    private final UserMfaSecretRepository mfaSecretRepository;
    private final MfaBackupCodeRepository backupCodeRepository;
    private final TrustedDeviceRepository trustedDeviceRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    private final Map<UUID, EmailOtp> emailOtpStore = new ConcurrentHashMap<>();

    private record EmailOtp(String code, Instant expiresAt) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }

    public MfaService(UserMfaSecretRepository mfaSecretRepository,
                      MfaBackupCodeRepository backupCodeRepository,
                      TrustedDeviceRepository trustedDeviceRepository,
                      UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      JavaMailSender mailSender,
                      MailProperties mailProperties) {
        this.mfaSecretRepository = mfaSecretRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.trustedDeviceRepository = trustedDeviceRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    // ── TOTP ────────────────────────────────────────────────────────

    public MfaSetupResponse setupMfa(UUID userId) {
        return setupTotp(userId);
    }

    public MfaSetupResponse setupTotp(UUID userId) {
        SecretGenerator generator = new DefaultSecretGenerator(32);
        String secret = generator.generate();

        UserMfaSecret existing = mfaSecretRepository.findById(userId).orElse(null);
        if (existing != null) {
            existing.setSecret(secret);
            mfaSecretRepository.save(existing);
        } else {
            mfaSecretRepository.save(UserMfaSecret.builder()
                    .userId(userId).secret(secret).build());
        }

        String email = userRepository.findById(userId).map(User::getEmail).orElse("user");
        String qrCodeUrl = "otpauth://totp/" + ISSUER + ":" + email
                + "?secret=" + secret + "&issuer=" + ISSUER;

        return MfaSetupResponse.builder().secret(secret).qrCodeUrl(qrCodeUrl).build();
    }

    @Transactional
    public List<String> enableTotp(UUID userId, String code) {
        if (!verifyTotpCode(userId, code)) {
            throw new IllegalArgumentException("Invalid TOTP code");
        }
        userRepository.findById(userId).ifPresent(user -> {
            user.setMfaEnabled(true);
            userRepository.save(user);
        });
        return generateBackupCodes(userId);
    }

    public void enableMfa(UUID userId, String code) {
        enableTotp(userId, code);
    }

    private boolean verifyTotpCode(UUID userId, String code) {
        UserMfaSecret mfaSecret = mfaSecretRepository.findByUserId(userId).orElse(null);
        if (mfaSecret == null) return false;
        CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        CodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, new SystemTimeProvider());
        return verifier.isValidCode(mfaSecret.getSecret(), code);
    }

    // ── Email OTP ───────────────────────────────────────────────────

    public void sendEmailOtp(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String code = generateNumericCode(EMAIL_OTP_LENGTH);
        emailOtpStore.put(userId, new EmailOtp(code,
                Instant.now().plus(EMAIL_OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES)));

        if (mailSender != null && mailProperties != null && mailProperties.isEnabled()) {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(mailProperties.getFrom());
                msg.setTo(user.getEmail());
                msg.setSubject("ContractIQ — Your login code");
                msg.setText("Your verification code is: " + code + "\n\n"
                        + "This code expires in " + EMAIL_OTP_EXPIRY_MINUTES + " minutes.\n"
                        + "If you didn't request this, ignore this email.");
                mailSender.send(msg);
                log.info("Email OTP sent to {}", user.getEmail());
            } catch (Exception e) {
                log.error("Failed to send email OTP: {}", e.getMessage());
                throw new RuntimeException("Failed to send verification email");
            }
        } else {
            log.info("Email OTP for {} (mail disabled): {}", user.getEmail(), code);
        }
    }

    @Transactional
    public void enableEmailOtp(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setMfaEnabled(true);
            userRepository.save(user);
        });
    }

    public boolean verifyEmailOtp(UUID userId, String code) {
        EmailOtp otp = emailOtpStore.get(userId);
        if (otp == null || otp.isExpired()) {
            emailOtpStore.remove(userId);
            return false;
        }
        if (otp.code().equals(code)) {
            emailOtpStore.remove(userId);
            return true;
        }
        return false;
    }

    // ── Unified verify ──────────────────────────────────────────────

    public boolean verifyCode(UUID userId, String code) {
        if (verifyTotpCode(userId, code)) return true;
        if (verifyEmailOtp(userId, code)) return true;
        if (verifyBackupCode(userId, code)) return true;
        return false;
    }

    // ── Backup Codes ────────────────────────────────────────────────

    @Transactional
    public List<String> generateBackupCodes(UUID userId) {
        backupCodeRepository.deleteByUserId(userId);
        List<String> plainCodes = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            String code = generateBackupCodeString();
            plainCodes.add(code);
            backupCodeRepository.save(MfaBackupCode.builder()
                    .userId(userId)
                    .codeHash(passwordEncoder.encode(code))
                    .build());
        }
        log.info("Generated {} backup codes for user {}", BACKUP_CODE_COUNT, userId);
        return plainCodes;
    }

    @Transactional
    public boolean verifyBackupCode(UUID userId, String code) {
        List<MfaBackupCode> unused = backupCodeRepository.findByUserIdAndUsedFalse(userId);
        for (MfaBackupCode bc : unused) {
            if (passwordEncoder.matches(code, bc.getCodeHash())) {
                bc.setUsed(true);
                bc.setUsedAt(Instant.now());
                backupCodeRepository.save(bc);
                log.info("Backup code used for user {} ({} remaining)", userId, unused.size() - 1);
                return true;
            }
        }
        return false;
    }

    public int getRemainingBackupCodes(UUID userId) {
        return backupCodeRepository.findByUserIdAndUsedFalse(userId).size();
    }

    // ── Trusted Devices ─────────────────────────────────────────────

    public String trustDevice(UUID userId, String userAgent, String ipAddress, int trustDays) {
        String token = UUID.randomUUID().toString();
        trustedDeviceRepository.save(TrustedDevice.builder()
                .userId(userId).deviceToken(token)
                .userAgent(userAgent).ipAddress(ipAddress)
                .expiresAt(Instant.now().plus(trustDays, ChronoUnit.DAYS))
                .build());
        log.info("Trusted device for user {} ({}d)", userId, trustDays);
        return token;
    }

    public boolean isDeviceTrusted(UUID userId, String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) return false;
        return trustedDeviceRepository.findByDeviceToken(deviceToken)
                .filter(d -> d.getUserId().equals(userId))
                .filter(d -> !d.isExpired())
                .map(d -> { d.setLastUsedAt(Instant.now()); trustedDeviceRepository.save(d); return true; })
                .orElse(false);
    }

    public List<TrustedDevice> getTrustedDevices(UUID userId) {
        return trustedDeviceRepository.findByUserIdOrderByLastUsedAtDesc(userId);
    }

    @Transactional
    public void revokeAllDevices(UUID userId) {
        trustedDeviceRepository.deleteByUserId(userId);
        log.info("All trusted devices revoked for user {}", userId);
    }

    // ── Admin Reset ─────────────────────────────────────────────────

    @Transactional
    public void adminResetMfa(UUID userId) {
        mfaSecretRepository.deleteById(userId);
        backupCodeRepository.deleteByUserId(userId);
        trustedDeviceRepository.deleteByUserId(userId);
        emailOtpStore.remove(userId);
        userRepository.findById(userId).ifPresent(user -> {
            user.setMfaEnabled(false);
            userRepository.save(user);
        });
        log.info("Admin reset MFA for user {}", userId);
    }

    @Transactional
    public void disableMfa(UUID userId) {
        adminResetMfa(userId);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private String generateNumericCode(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }

    private String generateBackupCodeString() {
        SecureRandom random = new SecureRandom();
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    @Scheduled(fixedRate = 3600000)
    public void cleanup() {
        trustedDeviceRepository.deleteExpired();
        emailOtpStore.entrySet().removeIf(e -> e.getValue().isExpired());
    }
}
