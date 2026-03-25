package com.legalpartner.model.dto.auth;

import jakarta.validation.constraints.NotBlank;
public record InviteAcceptRequest(@NotBlank String token, @NotBlank String password, String displayName) {}
