package com.legalpartner.service;

import com.legalpartner.config.EncryptionConfig;
import org.jasypt.encryption.StringEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    private EncryptionService service;

    @BeforeEach
    void setUp() {
        // Create a real encryptor for testing
        EncryptionConfig config = new EncryptionConfig();
        // Use reflection to set the password since @Value won't work in unit tests
        try {
            var field = EncryptionConfig.class.getDeclaredField("encryptorPassword");
            field.setAccessible(true);
            field.set(config, "test-encryption-key-for-tests");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        StringEncryptor encryptor = config.stringEncryptor();
        service = new EncryptionService(encryptor);
    }

    @Test
    void encryptAndDecryptRoundTrip() {
        String plaintext = "This is a confidential contract clause about liability caps.";
        String encrypted = service.encrypt(plaintext);

        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(encrypted).isNotBlank();

        String decrypted = service.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void sameTextProducesDifferentCiphertext() {
        String plaintext = "Same text encrypted twice";
        String encrypted1 = service.encrypt(plaintext);
        String encrypted2 = service.encrypt(plaintext);

        // Random salt/IV → different ciphertext each time
        assertThat(encrypted1).isNotEqualTo(encrypted2);

        // But both decrypt to same plaintext
        assertThat(service.decrypt(encrypted1)).isEqualTo(plaintext);
        assertThat(service.decrypt(encrypted2)).isEqualTo(plaintext);
    }

    @Test
    void unicodeTextRoundTrips() {
        String plaintext = "合同条款 — ₹500,000 liability cap — §12.3 Indemnification";
        String encrypted = service.encrypt(plaintext);
        assertThat(service.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void longTextRoundTrips() {
        String plaintext = "A".repeat(10000);
        String encrypted = service.encrypt(plaintext);
        assertThat(service.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void emptyStringRoundTrips() {
        String encrypted = service.encrypt("");
        assertThat(service.decrypt(encrypted)).isEmpty();
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String encrypted = service.encrypt("secret data");

        // Create a service with a different key
        EncryptionConfig otherConfig = new EncryptionConfig();
        try {
            var field = EncryptionConfig.class.getDeclaredField("encryptorPassword");
            field.setAccessible(true);
            field.set(otherConfig, "different-encryption-key-wrong");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        EncryptionService otherService = new EncryptionService(otherConfig.stringEncryptor());

        assertThatThrownBy(() -> otherService.decrypt(encrypted))
                .isInstanceOf(Exception.class);
    }
}
