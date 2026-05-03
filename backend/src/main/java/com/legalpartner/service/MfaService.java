package com.legalpartner.service;

import com.legalpartner.model.dto.auth.MfaSetupResponse;
import com.legalpartner.model.entity.UserMfaSecret;
import com.legalpartner.repository.UserMfaSecretRepository;
import com.legalpartner.repository.UserRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MfaService {

    private static final String ISSUER = "ContractIQ";

    private final UserMfaSecretRepository mfaSecretRepository;
    private final UserRepository userRepository;

    public MfaSetupResponse setupMfa(UUID userId) {
        SecretGenerator generator = new DefaultSecretGenerator(32);
        String secret = generator.generate();

        UserMfaSecret existing = mfaSecretRepository.findById(userId).orElse(null);
        if (existing != null) {
            existing.setSecret(secret);
            mfaSecretRepository.save(existing);
        } else {
            mfaSecretRepository.save(UserMfaSecret.builder()
                    .userId(userId)
                    .secret(secret)
                    .build());
        }

        String email = userRepository.findById(userId)
                .map(u -> u.getEmail())
                .orElse("user");
        String qrCodeUrl = "otpauth://totp/" + ISSUER + ":" + email + "?secret=" + secret + "&issuer=" + ISSUER;

        return MfaSetupResponse.builder()
                .secret(secret)
                .qrCodeUrl(qrCodeUrl)
                .build();
    }

    public boolean verifyCode(UUID userId, String code) {
        UserMfaSecret mfaSecret = mfaSecretRepository.findByUserId(userId).orElse(null);
        if (mfaSecret == null) return false;

        CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        TimeProvider timeProvider = new SystemTimeProvider();
        CodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        return verifier.isValidCode(mfaSecret.getSecret(), code);
    }

    public void enableMfa(UUID userId, String code) {
        if (!verifyCode(userId, code)) {
            throw new IllegalArgumentException("Invalid MFA code");
        }
        userRepository.findById(userId).ifPresent(user -> {
            user.setMfaEnabled(true);
            userRepository.save(user);
        });
    }

    public void disableMfa(UUID userId) {
        mfaSecretRepository.deleteById(userId);
        userRepository.findById(userId).ifPresent(user -> {
            user.setMfaEnabled(false);
            userRepository.save(user);
        });
    }
}
