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

import java.util.Base64;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocuSignProvider implements IntegrationProvider {

    private final IntegrationProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderId() { return "DOCUSIGN"; }

    @Override
    public String getDisplayName() { return "DocuSign"; }

    @Override
    public String getCategory() { return "E_SIGNATURE"; }

    @Override
    public boolean isOAuth() { return true; }

    private String getBaseUrl() {
        return "demo".equals(properties.getDocusign().getEnvironment())
                ? "https://account-d.docusign.com"
                : "https://account.docusign.com";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        return UriComponentsBuilder.fromHttpUrl(getBaseUrl() + "/oauth/auth")
                .queryParam("response_type", "code")
                .queryParam("scope", "signature")
                .queryParam("client_id", properties.getDocusign().getClientId())
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
        headers.setBasicAuth(properties.getDocusign().getClientId(), properties.getDocusign().getClientSecret());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(getBaseUrl() + "/oauth/token", request, String.class);
        return parseTokenResponse(response.getBody());
    }

    @Override
    public String refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(properties.getDocusign().getClientId(), properties.getDocusign().getClientSecret());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(getBaseUrl() + "/oauth/token", request, String.class);
        TokenResponse tr = parseTokenResponse(response.getBody());
        return tr != null ? tr.getAccessToken() : null;
    }

    /** Call after token exchange to get the user's accountId and baseUri. Returns JSON string for config storage. */
    public String fetchUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/oauth/userinfo", HttpMethod.GET, request, String.class);
        try {
            JsonNode node = objectMapper.readTree(response.getBody());
            JsonNode account = node.path("accounts").get(0);
            String accountId = account.path("account_id").asText();
            String baseUri = account.path("base_uri").asText();
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "accountId", accountId,
                    "baseUri", baseUri
            ));
        } catch (Exception e) {
            log.error("Failed to fetch DocuSign user info: {}", e.getMessage());
            return "{}";
        }
    }

    /** Send a document for e-signature via DocuSign Envelopes API. */
    public String sendEnvelope(String accessToken, String accountId, String baseUri,
                               String documentBase64, String documentName,
                               String signerEmail, String signerName, String emailSubject) {
        try {
            String envelopeJson = objectMapper.writeValueAsString(java.util.Map.of(
                    "emailSubject", emailSubject != null ? emailSubject : "Please sign: " + documentName,
                    "documents", new Object[]{java.util.Map.of(
                            "documentBase64", documentBase64,
                            "name", documentName,
                            "fileExtension", "pdf",
                            "documentId", "1"
                    )},
                    "recipients", java.util.Map.of(
                            "signers", new Object[]{java.util.Map.of(
                                    "email", signerEmail,
                                    "name", signerName,
                                    "recipientId", "1",
                                    "routingOrder", "1",
                                    "tabs", java.util.Map.of(
                                            "signHereTabs", new Object[]{java.util.Map.of(
                                                    "anchorString", "/sn1/",
                                                    "anchorUnits", "pixels",
                                                    "anchorXOffset", "20",
                                                    "anchorYOffset", "10"
                                            )}
                                    )
                            )}
                    ),
                    "status", "sent"
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String url = baseUri + "/restapi/v2.1/accounts/" + accountId + "/envelopes";
            HttpEntity<String> request = new HttpEntity<>(envelopeJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("envelopeId").asText();
        } catch (Exception e) {
            log.error("Failed to send DocuSign envelope: {}", e.getMessage());
            throw new RuntimeException("DocuSign envelope failed: " + e.getMessage());
        }
    }

    /**
     * Send envelope with multiple recipients: signers, reviewers (approve), and CC.
     * Recipients are ordered by routingOrder — DocuSign sends to each in sequence.
     */
    public String sendMultiRecipientEnvelope(String accessToken, String accountId, String baseUri,
                                              String documentBase64, String documentName, String emailSubject,
                                              java.util.List<com.legalpartner.controller.IntegrationController.SignatureRecipient> recipients) {
        try {
            java.util.List<java.util.Map<String, Object>> signers = new java.util.ArrayList<>();
            java.util.List<java.util.Map<String, Object>> carbonCopies = new java.util.ArrayList<>();
            int recipientCounter = 1;

            for (var r : recipients) {
                java.util.Map<String, Object> recipient = new java.util.LinkedHashMap<>();
                recipient.put("email", r.email());
                recipient.put("name", r.name());
                recipient.put("recipientId", String.valueOf(recipientCounter++));
                recipient.put("routingOrder", String.valueOf(r.routingOrder()));

                if ("CC".equalsIgnoreCase(r.role())) {
                    carbonCopies.add(recipient);
                } else {
                    // Both SIGNER and REVIEWER are "signers" in DocuSign — reviewers just approve
                    signers.add(recipient);
                }
            }

            // If no recipients were provided, fail early
            if (signers.isEmpty() && carbonCopies.isEmpty()) {
                throw new IllegalArgumentException("At least one signer is required");
            }

            java.util.Map<String, Object> recipientsMap = new java.util.LinkedHashMap<>();
            if (!signers.isEmpty()) recipientsMap.put("signers", signers);
            if (!carbonCopies.isEmpty()) recipientsMap.put("carbonCopies", carbonCopies);

            String fileExt = documentName.endsWith(".pdf") ? "pdf" : "html";
            java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("emailSubject", emailSubject);
            envelope.put("documents", new Object[]{java.util.Map.of(
                    "documentBase64", documentBase64,
                    "name", documentName,
                    "fileExtension", fileExt,
                    "documentId", "1"
            )});
            envelope.put("recipients", recipientsMap);
            envelope.put("status", "sent");

            String envelopeJson = objectMapper.writeValueAsString(envelope);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String url = baseUri + "/restapi/v2.1/accounts/" + accountId + "/envelopes";
            HttpEntity<String> request = new HttpEntity<>(envelopeJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("envelopeId").asText();
        } catch (Exception e) {
            log.error("Failed to send multi-recipient DocuSign envelope: {}", e.getMessage());
            throw new RuntimeException("DocuSign envelope failed: " + e.getMessage());
        }
    }

    /** Get envelope status. */
    public String getEnvelopeStatus(String accessToken, String accountId, String baseUri, String envelopeId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        String url = baseUri + "/restapi/v2.1/accounts/" + accountId + "/envelopes/" + envelopeId;
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
        try {
            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("status").asText();
        } catch (Exception e) {
            log.error("Failed to get envelope status: {}", e.getMessage());
            return "unknown";
        }
    }

    /** Download the combined signed PDF (all documents merged) from a completed envelope. */
    public byte[] downloadSignedDocument(String accessToken, String accountId, String baseUri, String envelopeId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            String url = baseUri + "/restapi/v2.1/accounts/" + accountId + "/envelopes/" + envelopeId + "/documents/combined";
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, request, byte[].class);
            log.info("Downloaded signed PDF for envelope {} ({} bytes)", envelopeId, response.getBody() != null ? response.getBody().length : 0);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to download signed document for envelope {}: {}", envelopeId, e.getMessage());
            return null;
        }
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
