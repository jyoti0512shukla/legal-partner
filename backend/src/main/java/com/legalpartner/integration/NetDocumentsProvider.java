package com.legalpartner.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.IntegrationProperties;
import com.legalpartner.storage.TokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
@Slf4j
public class NetDocumentsProvider implements IntegrationProvider {

    private final IntegrationProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String AUTH_URL = "https://vault.netvoyage.com/neWeb2/OAuth.aspx";
    private static final String TOKEN_URL = "https://vault.netvoyage.com/neWeb2/OAuth.aspx";

    @Override
    public String getProviderId() { return "NETDOCUMENTS"; }

    @Override
    public String getDisplayName() { return "NetDocuments"; }

    @Override
    public String getCategory() { return "DOCUMENT_MANAGEMENT"; }

    @Override
    public boolean isOAuth() { return true; }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        return UriComponentsBuilder.fromHttpUrl(AUTH_URL)
                .queryParam("response_type", "code")
                .queryParam("scope", "full")
                .queryParam("client_id", properties.getNetDocuments().getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    @Override
    public TokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(properties.getNetDocuments().getClientId(), properties.getNetDocuments().getClientSecret());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);
        return parseTokenResponse(response.getBody());
    }

    @Override
    public String refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(properties.getNetDocuments().getClientId(), properties.getNetDocuments().getClientSecret());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);
        TokenResponse tr = parseTokenResponse(response.getBody());
        return tr != null ? tr.getAccessToken() : null;
    }

    private TokenResponse parseTokenResponse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new TokenResponse(
                    node.path("access_token").asText(),
                    node.has("refresh_token") ? node.path("refresh_token").asText() : null,
                    node.has("expires_in") ? node.path("expires_in").asInt() : null,
                    node.has("token_type") ? node.path("token_type").asText() : "Bearer"
            );
        } catch (Exception e) {
            log.error("Failed to parse token response: {}", e.getMessage());
            return null;
        }
    }
}
