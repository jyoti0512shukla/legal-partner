package com.legalpartner.controller;

import com.legalpartner.config.IntegrationProperties;
import com.legalpartner.repository.UserRepository;
import com.legalpartner.service.IntegrationService;
import com.legalpartner.service.IntegrationService.IntegrationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService integrationService;
    private final IntegrationProperties integrationProperties;
    private final UserRepository userRepository;

    @GetMapping("/connections")
    public List<IntegrationStatus> getConnections(Authentication auth) {
        UUID userId = getUserId(auth);
        return integrationService.getConnectionStatuses(userId);
    }

    @GetMapping("/auth-url")
    public AuthUrlResponse getAuthUrl(
            @RequestParam String provider,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        String url = integrationService.getAuthorizationUrl(provider, userId);
        return new AuthUrlResponse(url);
    }

    @GetMapping("/callback")
    public RedirectView handleCallback(
            @RequestParam String code,
            @RequestParam String state
    ) {
        integrationService.handleOAuthCallback(code, state);
        String providerId = state.contains("::") ? state.split("::")[1] : "";
        return new RedirectView(integrationProperties.getFrontendUrl() + "/settings?tab=integrations&connected=" + providerId);
    }

    @PostMapping("/slack/configure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void configureSlack(
            @RequestBody SlackConfigRequest request,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        integrationService.saveWebhookConfig(userId, "SLACK", request.webhookUrl());
    }

    @PostMapping("/teams/configure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void configureTeams(
            @RequestBody TeamsConfigRequest request,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        integrationService.saveWebhookConfig(userId, "MICROSOFT_TEAMS", request.webhookUrl());
    }

    @DeleteMapping("/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(
            @RequestParam String provider,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        integrationService.disconnect(userId, provider);
    }

    private UUID getUserId(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"))
                .getId();
    }

    public record AuthUrlResponse(String url) {}
    public record SlackConfigRequest(String webhookUrl) {}
    public record TeamsConfigRequest(String webhookUrl) {}
}
