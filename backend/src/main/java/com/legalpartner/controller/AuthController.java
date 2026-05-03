package com.legalpartner.controller;

import com.legalpartner.model.dto.auth.*;
import com.legalpartner.security.LegalPartnerUserDetails;
import com.legalpartner.service.AuthService;
import com.legalpartner.service.JwtService;
import com.legalpartner.service.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final MfaService mfaService;
    private final com.legalpartner.service.InviteService inviteService;
    private final com.legalpartner.repository.UserRepository userRepository;

    @Value("${legalpartner.jwt.expiration-minutes:60}")
    private int jwtExpirationMinutes;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    // ── Cookie helpers ────────────────────────────────────────────────────────

    private void setJwtCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from("jwt", token)
                .httpOnly(true)
                .secure(sslEnabled)           // true in prod (HTTPS), false in local dev
                .sameSite("Strict")
                .path("/api")
                .maxAge(Duration.ofMinutes(jwtExpirationMinutes))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearJwtCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("jwt", "")
                .httpOnly(true)
                .secure(sslEnabled)
                .sameSite("Strict")
                .path("/api")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
        AuthResponse authResponse = authService.register(request, httpRequest.getRemoteAddr());
        if (authResponse.getToken() != null) {
            setJwtCookie(httpResponse, authResponse.getToken());
        }
        return authResponse;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest httpRequest,
                              HttpServletResponse httpResponse) {
        AuthResponse authResponse = authService.login(request, httpRequest.getRemoteAddr());
        if (authResponse.getToken() != null) {
            setJwtCookie(httpResponse, authResponse.getToken());
        }
        return authResponse;
    }

    @PostMapping("/mfa/validate")
    public AuthResponse validateMfa(@Valid @RequestBody MfaValidateRequest request,
                                    HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        AuthResponse authResponse = authService.validateMfa(request.getToken(), request.getCode(), httpRequest.getRemoteAddr());
        if (authResponse.getToken() != null) {
            setJwtCookie(httpResponse, authResponse.getToken());
        }
        return authResponse;
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal LegalPartnerUserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        String email;
        if (request.getToken() != null && !request.getToken().isBlank()) {
            email = authService.getEmailFromTempToken(request.getToken());
            if (email == null) {
                return ResponseEntity.status(401).build();
            }
        } else if (userDetails != null) {
            email = userDetails.getUsername();
        } else {
            return ResponseEntity.status(401).build();
        }
        authService.changePassword(email, request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public UserInfo me(@AuthenticationPrincipal LegalPartnerUserDetails userDetails) {
        if (userDetails == null) {
            return null;
        }
        return UserInfo.from(userDetails.getUser());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        // Revoke the JWT so it can't be reused
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwtService.revokeToken(authHeader.substring(7));
        }
        clearJwtCookie(response);
        return ResponseEntity.ok().build();
    }

    // ── MFA Setup ────────────────────────────────────────────────────

    @PostMapping("/mfa/setup")
    public MfaSetupResponse setupMfa(@AuthenticationPrincipal LegalPartnerUserDetails userDetails) {
        if (userDetails == null) throw new IllegalArgumentException("Not authenticated");
        return mfaService.setupTotp(userDetails.getUser().getId());
    }

    /** Verify TOTP code and enable MFA. Returns 10 backup codes. */
    @PostMapping("/mfa/verify")
    public java.util.Map<String, Object> verifyMfa(
            @AuthenticationPrincipal LegalPartnerUserDetails userDetails,
            @Valid @RequestBody MfaVerifyRequest request) {
        if (userDetails == null) throw new IllegalArgumentException("Not authenticated");
        List<String> backupCodes = mfaService.enableTotp(userDetails.getUser().getId(), request.getCode());
        return java.util.Map.of("backupCodes", backupCodes, "message", "MFA enabled. Save your backup codes.");
    }

    /** Enable email OTP as MFA method. */
    @PostMapping("/mfa/enable-email")
    public ResponseEntity<Void> enableEmailMfa(@AuthenticationPrincipal LegalPartnerUserDetails userDetails) {
        if (userDetails == null) throw new IllegalArgumentException("Not authenticated");
        mfaService.enableEmailOtp(userDetails.getUser().getId());
        return ResponseEntity.ok().build();
    }

    /** Send email OTP during login (called after password verified, before full auth). */
    @PostMapping("/mfa/send-email-otp")
    public ResponseEntity<Void> sendEmailOtp(@RequestBody java.util.Map<String, String> body) {
        String email = body.get("email");
        if (email == null) throw new IllegalArgumentException("Email required");
        userRepository.findByEmail(email).ifPresent(user ->
                mfaService.sendEmailOtp(user.getId()));
        return ResponseEntity.ok().build(); // Always 200 — don't reveal if email exists
    }

    @PostMapping("/mfa/disable")
    public ResponseEntity<Void> disableMfa(@AuthenticationPrincipal LegalPartnerUserDetails userDetails) {
        if (userDetails == null) throw new IllegalArgumentException("Not authenticated");
        mfaService.disableMfa(userDetails.getUser().getId());
        return ResponseEntity.ok().build();
    }

    /** Get backup code count. */
    @GetMapping("/mfa/backup-codes/remaining")
    public java.util.Map<String, Integer> backupCodesRemaining(
            @AuthenticationPrincipal LegalPartnerUserDetails userDetails) {
        if (userDetails == null) throw new IllegalArgumentException("Not authenticated");
        return java.util.Map.of("remaining", mfaService.getRemainingBackupCodes(userDetails.getUser().getId()));
    }

    /** Regenerate backup codes. */
    @PostMapping("/mfa/backup-codes/regenerate")
    public java.util.Map<String, Object> regenerateBackupCodes(
            @AuthenticationPrincipal LegalPartnerUserDetails userDetails) {
        if (userDetails == null) throw new IllegalArgumentException("Not authenticated");
        List<String> codes = mfaService.generateBackupCodes(userDetails.getUser().getId());
        return java.util.Map.of("backupCodes", codes);
    }

    /** List trusted devices. */
    @GetMapping("/mfa/trusted-devices")
    public List<com.legalpartner.model.entity.TrustedDevice> trustedDevices(
            @AuthenticationPrincipal LegalPartnerUserDetails userDetails) {
        if (userDetails == null) throw new IllegalArgumentException("Not authenticated");
        return mfaService.getTrustedDevices(userDetails.getUser().getId());
    }

    /** Revoke all trusted devices. */
    @PostMapping("/mfa/trusted-devices/revoke")
    public ResponseEntity<Void> revokeDevices(@AuthenticationPrincipal LegalPartnerUserDetails userDetails) {
        if (userDetails == null) throw new IllegalArgumentException("Not authenticated");
        mfaService.revokeAllDevices(userDetails.getUser().getId());
        return ResponseEntity.ok().build();
    }

    // ── Token Validation ────────────────────────────────────────────────

    @GetMapping("/validate-token")
    public java.util.Map<String, String> validateToken(@RequestParam String token, @RequestParam String type) {
        String email = inviteService.validateTokenAndGetEmail(token, type);
        return java.util.Map.of("email", email, "valid", "true");
    }

    // ── Forgot Password ───────────────────────────────────────────────────

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        inviteService.requestPasswordReset(request.email());
        return ResponseEntity.ok().build();  // Always 200 — don't reveal if email exists
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        inviteService.resetPassword(request.token(), request.password());
        return ResponseEntity.ok().build();
    }

    // ── Accept Invite ─────────────────────────────────────────────────────

    @PostMapping("/accept-invite")
    public ResponseEntity<Void> acceptInvite(@Valid @RequestBody InviteAcceptRequest request) {
        inviteService.acceptInvite(request.token(), request.password(), request.displayName());
        return ResponseEntity.ok().build();
    }
}
