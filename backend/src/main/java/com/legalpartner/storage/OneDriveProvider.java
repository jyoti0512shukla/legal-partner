package com.legalpartner.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.CloudStorageProperties;
import com.legalpartner.model.dto.cloud.CloudFileItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OneDriveProvider implements CloudStorageProvider {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String SCOPES = "offline_access Files.Read Files.ReadWrite User.Read";

    private final CloudStorageProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String getAuthUrl() {
        return String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/authorize",
                properties.getMicrosoft().getTenant());
    }

    private String getTokenUrl() {
        return String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/token",
                properties.getMicrosoft().getTenant());
    }

    @Override
    public String getProviderId() {
        return "ONEDRIVE";
    }

    @Override
    public String getDisplayName() {
        return "OneDrive / SharePoint";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        return UriComponentsBuilder.fromHttpUrl(getAuthUrl())
                .queryParam("client_id", properties.getMicrosoft().getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPES)
                .queryParam("response_mode", "query")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    @Override
    public TokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("client_id", properties.getMicrosoft().getClientId());
        body.add("client_secret", properties.getMicrosoft().getClientSecret());
        body.add("redirect_uri", redirectUri);
        body.add("grant_type", "authorization_code");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(getTokenUrl(), request, String.class);
        return parseTokenResponse(response.getBody());
    }

    @Override
    public String refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", properties.getMicrosoft().getClientId());
        body.add("client_secret", properties.getMicrosoft().getClientSecret());
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(getTokenUrl(), request, String.class);
        TokenResponse tr = parseTokenResponse(response.getBody());
        return tr != null ? tr.getAccessToken() : null;
    }

    @Override
    public List<CloudFileItem> listFiles(String accessToken, String folderId) {
        String endpoint = folderId == null || folderId.isEmpty() || "root".equals(folderId)
                ? GRAPH_BASE + "/me/drive/root/children"
                : GRAPH_BASE + "/me/drive/items/" + folderId + "/children";
        String url = endpoint + "?$top=100&$select=id,name,size,file,folder";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
        return parseFileList(response.getBody());
    }

    @Override
    public byte[] downloadFile(String accessToken, String fileId) {
        String url = GRAPH_BASE + "/me/drive/items/" + fileId + "/content";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, request, byte[].class);
        return response.getBody();
    }

    @Override
    public String uploadFile(String accessToken, String folderId, String fileName, byte[] content, String mimeType) {
        try {
            String endpoint = (folderId == null || folderId.isEmpty() || "root".equals(folderId))
                    ? GRAPH_BASE + "/me/drive/root:/" + encodePath(fileName) + ":/content"
                    : GRAPH_BASE + "/me/drive/items/" + folderId + ":/" + encodePath(fileName) + ":/content";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.parseMediaType(mimeType != null ? mimeType : "text/html"));
            HttpEntity<byte[]> request = new HttpEntity<>(content, headers);
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.PUT, request, String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("id").asText();
        } catch (Exception e) {
            log.error("OneDrive upload failed: {}", e.getMessage());
            throw new RuntimeException("Failed to upload to OneDrive: " + e.getMessage());
        }
    }

    private String encodePath(String fileName) {
        return java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
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

    private List<CloudFileItem> parseFileList(String json) {
        List<CloudFileItem> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode values = root.path("value");
            for (JsonNode v : values) {
                boolean folder = v.has("folder");
                Long size = v.has("size") ? v.path("size").asLong() : null;
                items.add(CloudFileItem.builder()
                        .id(v.path("id").asText())
                        .name(v.path("name").asText())
                        .folder(folder)
                        .mimeType(v.has("file") && v.path("file").has("mimeType")
                                ? v.path("file").path("mimeType").asText() : null)
                        .size(size)
                        .path(v.path("name").asText())
                        .build());
            }
        } catch (Exception e) {
            log.error("Failed to parse file list: {}", e.getMessage());
        }
        return items;
    }
}
