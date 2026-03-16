package com.legalpartner.model.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    /** Optional: when password expired, client sends the temp token instead of Bearer auth */
    private String token;

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @NotBlank(message = "New password is required")
    @Size(min = 12, max = 128)
    private String newPassword;
}
