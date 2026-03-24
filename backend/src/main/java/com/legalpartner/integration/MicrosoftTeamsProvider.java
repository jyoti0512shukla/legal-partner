package com.legalpartner.integration;

import com.legalpartner.storage.TokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class MicrosoftTeamsProvider implements IntegrationProvider {

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getProviderId() { return "MICROSOFT_TEAMS"; }

    @Override
    public String getDisplayName() { return "Microsoft Teams"; }

    @Override
    public String getCategory() { return "NOTIFICATIONS"; }

    @Override
    public boolean isOAuth() { return false; }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) { return null; }

    @Override
    public TokenResponse exchangeCodeForTokens(String code, String redirectUri) { return null; }

    @Override
    public String refreshAccessToken(String refreshToken) { return null; }

    /** Send a notification to a Microsoft Teams incoming webhook URL. */
    public void sendNotification(String webhookUrl, String title, String text) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Invalid Teams webhook URL");
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String payload = String.format(
                    "{\"@type\":\"MessageCard\",\"@context\":\"http://schema.org/extensions\","
                    + "\"summary\":\"%s\",\"themeColor\":\"6366f1\","
                    + "\"title\":\"%s\",\"text\":\"%s\"}",
                    title.replace("\"", "\\\""),
                    title.replace("\"", "\\\""),
                    text.replace("\"", "\\\"")
            );
            HttpEntity<String> request = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(webhookUrl, request, String.class);
        } catch (Exception e) {
            log.error("Teams notification failed: {}", e.getMessage());
        }
    }
}
