package com.legalpartner.model.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String token;
    private UserInfo user;
    private boolean mfaRequired;
    private boolean passwordExpired;
    private boolean accountLocked;
    private Instant lockedUntil;
}
