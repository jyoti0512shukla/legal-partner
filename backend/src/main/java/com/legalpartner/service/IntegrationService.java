package com.legalpartner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.IntegrationProperties;
import com.legalpartner.integration.DocuSignProvider;
import com.legalpartner.integration.IntegrationProvider;
import com.legalpartner.model.entity.IntegrationConnection;
import com.legalpartner.repository.IntegrationConnectionRepository;
import com.legalpartner.storage.TokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationService {

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationProperties properties;
    private final List<IntegrationProvider> providers;
    private final DocuSignProvider docuSignProvider;
    private final ObjectMapper objectMapper;

    public record IntegrationStatus(String provider, String displayName, String category,
                                     boolean connected, String description) {}

    public List<IntegrationStatus> getConnectionStatuses(UUID userId) {
        List<IntegrationStatus> result = new ArrayList<>();
        for (IntegrationProvider p : providers) {
            if (!isProviderEnabled(p.getProviderId())) continue;
            boolean connected = connectionRepository.existsByUserIdAndProvider(userId, p.getProviderId());
            result.add(new IntegrationStatus(
                    p.getProviderId(), p.getDisplayName(), p.getCategory(), connected,
                    getDescription(p.getProviderId())
            ));
        }
        return result;
    }

    public String getAuthorizationUrl(String providerId, UUID userId) {
        IntegrationProvider provider = getProvider(providerId);
        if (provider == null || !provider.isOAuth())
            throw new IllegalArgumentException("Unknown or non-OAuth provider: " + providerId);
        String redirectUri = properties.getBackendUrl() + "/api/v1/integrations/callback";
        String state = userId.toString() + "::" + providerId;
        return provider.buildAuthorizationUrl(redirectUri, state);
    }

    @Transactional
    public void handleOAuthCallback(String code, String state) {
        String[] parts = state.split("::");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid state");
        UUID userId = UUID.fromString(parts[0]);
        String providerId = parts[1];
        IntegrationProvider provider = getProvider(providerId);
        if (provider == null) throw new IllegalArgumentException("Unknown provider: " + providerId);

        String redirectUri = properties.getBackendUrl() + "/api/v1/integrations/callback";
        TokenResponse tr = provider.exchangeCodeForTokens(code, redirectUri);
        if (tr == null) throw new RuntimeException("Failed to exchange code for tokens");

        String config = "{}";
        if ("DOCUSIGN".equals(providerId)) {
            config = docuSignProvider.fetchUserInfo(tr.getAccessToken());
        }

        IntegrationConnection conn = connectionRepository.findByUserIdAndProvider(userId, providerId)
                .orElse(IntegrationConnection.builder()
                        .userId(userId)
                        .provider(providerId)
                        .build());
        conn.setAccessToken(tr.getAccessToken());
        conn.setRefreshToken(tr.getRefreshToken());
        conn.setTokenExpiresAt(tr.getTokenExpiresAt());
        conn.setConfig(config);
        connectionRepository.save(conn);
        log.info("Connected {} for user {}", providerId, userId);
    }

    @Transactional
    public void saveWebhookConfig(UUID userId, String providerId, String webhookUrl) {
        if (!"SLACK".equals(providerId) && !"MICROSOFT_TEAMS".equals(providerId))
            throw new IllegalArgumentException("Webhook config only for Slack or Microsoft Teams");
        if ("SLACK".equals(providerId) && (webhookUrl == null || !webhookUrl.startsWith("https://hooks.slack.com/")))
            throw new IllegalArgumentException("Invalid Slack webhook URL");
        if ("MICROSOFT_TEAMS".equals(providerId) && (webhookUrl == null || webhookUrl.isBlank()))
            throw new IllegalArgumentException("Invalid Teams webhook URL");

        IntegrationConnection conn = connectionRepository.findByUserIdAndProvider(userId, providerId)
                .orElse(IntegrationConnection.builder()
                        .userId(userId)
                        .provider(providerId)
                        .build());
        try {
            conn.setConfig(objectMapper.writeValueAsString(Map.of("webhookUrl", webhookUrl)));
        } catch (Exception e) {
            conn.setConfig("{\"webhookUrl\":\"" + webhookUrl + "\"}");
        }
        connectionRepository.save(conn);
        log.info("Configured Slack webhook for user {}", userId);
    }

    @Transactional
    public void disconnect(UUID userId, String providerId) {
        connectionRepository.deleteByUserIdAndProvider(userId, providerId);
        log.info("Disconnected {} for user {}", providerId, userId);
    }

    public String ensureValidToken(UUID userId, String providerId) {
        IntegrationConnection conn = connectionRepository.findByUserIdAndProvider(userId, providerId)
                .orElseThrow(() -> new IllegalStateException("Not connected to " + providerId));
        if (conn.getTokenExpiresAt() != null && conn.getTokenExpiresAt().isBefore(Instant.now().plusSeconds(60))) {
            if (conn.getRefreshToken() != null) {
                IntegrationProvider provider = getProvider(providerId);
                String newAccess = provider.refreshAccessToken(conn.getRefreshToken());
                if (newAccess != null) {
                    conn.setAccessToken(newAccess);
                    conn.setTokenExpiresAt(Instant.now().plusSeconds(3600));
                    connectionRepository.save(conn);
                }
            }
        }
        return conn.getAccessToken();
    }

    public IntegrationConnection getConnection(UUID userId, String providerId) {
        return connectionRepository.findByUserIdAndProvider(userId, providerId)
                .orElseThrow(() -> new IllegalStateException("Not connected to " + providerId));
    }

    private IntegrationProvider getProvider(String providerId) {
        return providers.stream()
                .filter(p -> p.getProviderId().equals(providerId))
                .findFirst()
                .orElse(null);
    }

    private boolean isProviderEnabled(String providerId) {
        return switch (providerId) {
            case "DOCUSIGN" -> properties.getDocusign().isEnabled();
            case "SLACK" -> properties.getSlack().isEnabled();
            case "NETDOCUMENTS" -> properties.getNetDocuments().isEnabled();
            case "IMANAGE" -> properties.getIManage().isEnabled();
            case "MICROSOFT_TEAMS" -> properties.getMicrosoftTeams().isEnabled();
            default -> false;
        };
    }

    private String getDescription(String providerId) {
        return switch (providerId) {
            case "DOCUSIGN" -> "Send contracts for electronic signature";
            case "SLACK" -> "Receive workflow notifications";
            case "NETDOCUMENTS" -> "Legal document management system";
            case "IMANAGE" -> "Enterprise document management";
            case "MICROSOFT_TEAMS" -> "Receive workflow notifications via Teams";
            default -> "";
        };
    }
}
