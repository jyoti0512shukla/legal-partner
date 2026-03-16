package com.legalpartner.config;

import com.legalpartner.model.entity.User;
import com.legalpartner.model.enums.UserRole;
import com.legalpartner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class AdminUserSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail("admin@legalpartner.local")) {
            return;
        }
        User admin = User.builder()
                .email("admin@legalpartner.local")
                .passwordHash(passwordEncoder.encode("Admin123!"))
                .displayName("Admin")
                .role(UserRole.ADMIN)
                .build();
        userRepository.save(admin);
        log.info("Seeded admin user: admin@legalpartner.local");
    }
}
