package com.legalpartner.model.dto.auth;

import jakarta.validation.constraints.NotBlank;
public record ForgotPasswordRequest(@NotBlank String email) {}
