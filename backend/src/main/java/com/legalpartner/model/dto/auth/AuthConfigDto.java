package com.legalpartner.model.dto.auth;

public record AuthConfigDto(int inviteExpiryHours, int inviteResendCooldownMin,
                            int passwordResetExpiryHours, int maxPasswordResetsPerHour,
                            int maxFailedLogins, int lockoutDurationMinutes) {}
