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
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DropboxProvider implements CloudStorageProvider {

    private static final String AUTH_URL = "https://www.dropbox.com/oauth2/authorize";
    private static final String TOKEN_URL = "https://api.dropboxapi.com/oauth2/token";
    private static final String API_URL = "https://api.dropboxapi.com/2";

    private final CloudStorageProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderId() {
        return "DROPBOX";
    }

    @Override
    public String getDisplayName() {
        return "Dropbox";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        return UriComponentsBuilder.fromHttpUrl(AUTH_URL)
                .queryParam("client_id", properties.getDropbox().getAppKey())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("token_access_type", "offline")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    @Override
    public TokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        String auth = Base64.getEncoder().encodeToString(
                (properties.getDropbox().getAppKey() + ":" + properties.getDropbox().getAppSecret()).getBytes());

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("grant_type", "authorization_code");
        body.add("redirect_uri", redirectUri);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", "Basic " + auth);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);
        return parseTokenResponse(response.getBody());
    }

    @Override
    public String refreshAccessToken(String refreshToken) {
        String auth = Base64.getEncoder().encodeToString(
                (properties.getDropbox().getAppKey() + ":" + properties.getDropbox().getAppSecret()).getBytes());

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", "Basic " + auth);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);
        TokenResponse tr = parseTokenResponse(response.getBody());
        return tr != null ? tr.getAccessToken() : null;
    }

    @Override
    public List<CloudFileItem> listFiles(String accessToken, String folderId) {
        String path = (folderId == null || folderId.isEmpty() || "root".equals(folderId)) ? "" : folderId;
        if (!path.isEmpty() && !path.startsWith("/")) path = "/" + path;

        String url = API_URL + "/files/list_folder";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        String pathJson = path.isEmpty() ? "\"\"" : "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String body = "{\"path\":" + pathJson + ",\"recursive\":false}";

        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        return parseFileList(response.getBody());
    }

    @Override
    public byte[] downloadFile(String accessToken, String fileId) {
        String path = fileId.startsWith("/") ? fileId : "/" + fileId;
        String url = "https://content.dropboxapi.com/2/files/download";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Dropbox-API-Arg", "{\"path\":\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, request, byte[].class);
        return response.getBody();
    }

    @Override
    public String uploadFile(String accessToken, String folderId, String fileName, byte[] content, String mimeType) {
        try {
            String path = (folderId == null || folderId.isEmpty() || "root".equals(folderId))
                    ? "/" + fileName
                    : (folderId.endsWith("/") ? folderId : folderId + "/") + fileName;
            String url = "https://content.dropboxapi.com/2/files/upload";

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.set("Dropbox-API-Arg", "{\"path\":\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"mode\":\"add\"}");
            HttpEntity<byte[]> request = new HttpEntity<>(content, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("path_display").asText();
        } catch (Exception e) {
            log.error("Dropbox upload failed: {}", e.getMessage());
            throw new RuntimeException("Failed to upload to Dropbox: " + e.getMessage());
        }
    }

    private TokenResponse parseTokenResponse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new TokenResponse(
                    node.path("access_token").asText(),
                    node.has("refresh_token") ? node.path("refresh_token").asText() : null,
                    node.has("expires_in") ? node.path("expires_in").asInt() : null,
                    "Bearer"
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
            JsonNode entries = root.path("entries");
            for (JsonNode e : entries) {
                String tag = e.path(".tag").asText();
                boolean folder = "folder".equals(tag);
                String path = e.path("path_display").asText();
                String name = e.path("name").asText();
                items.add(CloudFileItem.builder()
                        .id(path)
                        .name(name)
                        .folder(folder)
                        .mimeType(null)
                        .size(e.has("size") ? e.path("size").asLong() : null)
                        .path(path)
                        .build());
            }
        } catch (Exception e) {
            log.error("Failed to parse file list: {}", e.getMessage());
        }
        return items;
    }
}
