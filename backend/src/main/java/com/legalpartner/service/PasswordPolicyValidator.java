package com.legalpartner.service;

import com.legalpartner.config.PasswordPolicyProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class PasswordPolicyValidator {

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password123", "123456", "12345678", "qwerty", "abc123",
            "monkey", "1234567", "letmein", "trustno1", "dragon", "baseball",
            "iloveyou", "master", "sunshine", "princess", "football", "shadow",
            "admin", "admin123", "root", "welcome", "login", "passw0rd"
    );

    private final PasswordPolicyProperties properties;

    public PasswordPolicyValidator(PasswordPolicyProperties properties) {
        this.properties = properties;
    }

    public List<String> validate(String password) {
        List<String> errors = new ArrayList<>();

        if (password == null || password.length() < properties.getMinLength()) {
            errors.add("Password must be at least " + properties.getMinLength() + " characters");
        }
        if (password != null && password.length() > properties.getMaxLength()) {
            errors.add("Password must be at most " + properties.getMaxLength() + " characters");
        }
        if (properties.isRequireUppercase() && (password == null || !Pattern.compile("[A-Z]").matcher(password).find())) {
            errors.add("Password must contain at least one uppercase letter");
        }
        if (properties.isRequireLowercase() && (password == null || !Pattern.compile("[a-z]").matcher(password).find())) {
            errors.add("Password must contain at least one lowercase letter");
        }
        if (properties.isRequireDigit() && (password == null || !Pattern.compile("\\d").matcher(password).find())) {
            errors.add("Password must contain at least one digit");
        }
        if (properties.isRequireSpecial() && (password == null || !Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]").matcher(password).find())) {
            errors.add("Password must contain at least one special character");
        }
        if (properties.isBlockCommon() && password != null && COMMON_PASSWORDS.contains(password.toLowerCase())) {
            errors.add("Password is too common");
        }

        return errors;
    }
}
