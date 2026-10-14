package com.legalpartner.service;

import com.legalpartner.model.dto.auth.RegisterRequest;
import com.legalpartner.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Public sign-up is refused unless explicitly enabled (firm deployments are invite-only). */
class SelfRegistrationTest {

    private final UserRepository users = mock(UserRepository.class);

    private AuthService service(boolean enabled) {
        AuthService s = new AuthService(users, mock(com.legalpartner.repository.PasswordHistoryRepository.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                mock(PasswordPolicyValidator.class),
                mock(JwtService.class),
                mock(com.legalpartner.config.JwtProperties.class),
                mock(com.legalpartner.config.PasswordPolicyProperties.class),
                mock(com.legalpartner.config.LockoutProperties.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(MfaService.class));
        ReflectionTestUtils.setField(s, "selfRegistrationEnabled", enabled);
        return s;
    }

    private static RegisterRequest request() {
        RegisterRequest r = new RegisterRequest();
        r.setEmail("stranger@example.com");
        r.setPassword("Str0ng-Passw0rd!");
        r.setDisplayName("Stranger");
        return r;
    }

    @Test
    void disabledByDefaultRefusesAndCreatesNoUser() {
        AuthService s = service(false);
        assertThat(s.isSelfRegistrationEnabled()).isFalse();
        assertThatThrownBy(() -> s.register(request(), "203.0.113.7"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("invite");
        verify(users, never()).save(any());
        verify(users, never()).existsByEmail(any());
    }

    @Test
    void enabledProceedsToNormalValidation() {
        AuthService s = service(true);
        Mockito.when(users.existsByEmail("stranger@example.com")).thenReturn(true);
        assertThatThrownBy(() -> s.register(request(), "203.0.113.7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }
}
