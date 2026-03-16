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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleDriveProvider implements CloudStorageProvider {

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String FILES_URL = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String SCOPES = "https://www.googleapis.com/auth/drive.file";

    private final CloudStorageProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderId() {
        return "GOOGLE_DRIVE";
    }

    @Override
    public String getDisplayName() {
        return "Google Drive";
    }

    @Override
    public String buildAuthorizationUrl(String redirectUri, String state) {
        return UriComponentsBuilder.fromHttpUrl(AUTH_URL)
                .queryParam("client_id", properties.getGoogle().getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPES)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    @Override
    public TokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("client_id", properties.getGoogle().getClientId());
        body.add("client_secret", properties.getGoogle().getClientSecret());
        body.add("redirect_uri", redirectUri);
        body.add("grant_type", "authorization_code");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);
        return parseTokenResponse(response.getBody());
    }

    @Override
    public String refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", properties.getGoogle().getClientId());
        body.add("client_secret", properties.getGoogle().getClientSecret());
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, request, String.class);
        TokenResponse tr = parseTokenResponse(response.getBody());
        return tr != null ? tr.getAccessToken() : null;
    }

    @Override
    public List<CloudFileItem> listFiles(String accessToken, String folderId) {
        String q = "trashed = false";
        if (folderId != null && !folderId.isEmpty() && !"root".equals(folderId)) {
            q = "'" + folderId + "' in parents and trashed = false";
        } else {
            q = "'root' in parents and trashed = false";
        }
        String url = UriComponentsBuilder.fromHttpUrl(FILES_URL)
                .queryParam("q", q)
                .queryParam("fields", "files(id,name,mimeType,size)")
                .queryParam("pageSize", 100)
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
        return parseFileList(response.getBody());
    }

    @Override
    public byte[] downloadFile(String accessToken, String fileId) {
        String url = FILES_URL + "/" + fileId + "?alt=media";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, request, byte[].class);
        return response.getBody();
    }

    @Override
    public String uploadFile(String accessToken, String folderId, String fileName, byte[] content, String mimeType) {
        try {
            String parentId = (folderId == null || folderId.isEmpty() || "root".equals(folderId)) ? "root" : folderId;
            String escapedName = fileName.replace("\\", "\\\\").replace("\"", "\\\"");
            String metadata = String.format("{\"name\":\"%s\",\"parents\":[\"%s\"]}", escapedName, parentId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.parseMediaType("multipart/related; boundary=boundary"));

            String body = "--boundary\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    + metadata + "\r\n"
                    + "--boundary\r\n"
                    + "Content-Type: " + (mimeType != null ? mimeType : "text/html") + "\r\n\r\n";
            byte[] bodyStart = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] bodyEnd = "\r\n--boundary--\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] fullBody = new byte[bodyStart.length + content.length + bodyEnd.length];
            System.arraycopy(bodyStart, 0, fullBody, 0, bodyStart.length);
            System.arraycopy(content, 0, fullBody, bodyStart.length, content.length);
            System.arraycopy(bodyEnd, 0, fullBody, bodyStart.length + content.length, bodyEnd.length);

            HttpEntity<byte[]> request = new HttpEntity<>(fullBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(UPLOAD_URL + "?uploadType=multipart", request, String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("id").asText();
        } catch (Exception e) {
            log.error("Google Drive upload failed: {}", e.getMessage());
            throw new RuntimeException("Failed to upload to Google Drive: " + e.getMessage());
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

    private List<CloudFileItem> parseFileList(String json) {
        List<CloudFileItem> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode files = root.path("files");
            for (JsonNode f : files) {
                String mimeType = f.has("mimeType") ? f.path("mimeType").asText() : null;
                boolean folder = "application/vnd.google-apps.folder".equals(mimeType);
                items.add(CloudFileItem.builder()
                        .id(f.path("id").asText())
                        .name(f.path("name").asText())
                        .folder(folder)
                        .mimeType(mimeType)
                        .size(f.has("size") ? f.path("size").asLong() : null)
                        .path(f.path("name").asText())
                        .build());
            }
        } catch (Exception e) {
            log.error("Failed to parse file list: {}", e.getMessage());
        }
        return items;
    }
}
