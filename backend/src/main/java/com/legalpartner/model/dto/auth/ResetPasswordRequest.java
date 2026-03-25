package com.legalpartner.model.dto.auth;

import jakarta.validation.constraints.NotBlank;
public record ResetPasswordRequest(@NotBlank String token, @NotBlank String password) {}
