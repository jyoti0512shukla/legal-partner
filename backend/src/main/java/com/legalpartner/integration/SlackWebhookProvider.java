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
public class SlackWebhookProvider implements IntegrationProvider {

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getProviderId() { return "SLACK"; }

    @Override
    public String getDisplayName() { return "Slack"; }

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

    /** Send a notification to a Slack webhook URL. */
    public void sendNotification(String webhookUrl, String text) {
        if (webhookUrl == null || !webhookUrl.startsWith("https://hooks.slack.com/")) {
            log.warn("Invalid Slack webhook URL");
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String payload = String.format("{\"text\":\"%s\"}", text.replace("\"", "\\\""));
            HttpEntity<String> request = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(webhookUrl, request, String.class);
        } catch (Exception e) {
            log.error("Slack notification failed: {}", e.getMessage());
        }
    }
}
