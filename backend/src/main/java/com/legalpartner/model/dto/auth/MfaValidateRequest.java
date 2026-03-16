package com.legalpartner.model.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class MfaValidateRequest {

    @NotBlank(message = "Token is required")
    private String token;

    @NotBlank(message = "MFA code is required")
    @Pattern(regexp = "\\d{6}", message = "MFA code must be 6 digits")
    private String code;
}
