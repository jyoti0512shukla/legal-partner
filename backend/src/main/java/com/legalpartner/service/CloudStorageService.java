package com.legalpartner.service;

import com.legalpartner.config.CloudStorageProperties;
import com.legalpartner.model.dto.cloud.CloudFileItem;
import com.legalpartner.model.dto.cloud.CloudStorageConnectionStatus;
import com.legalpartner.model.entity.CloudStorageConnection;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.repository.CloudStorageConnectionRepository;
import com.legalpartner.repository.UserRepository;
import com.legalpartner.storage.CloudStorageProvider;
import com.legalpartner.storage.TokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudStorageService {

    private final CloudStorageConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final CloudStorageProperties properties;
    private final List<CloudStorageProvider> providers;

    public List<CloudStorageConnectionStatus> getConnectionStatuses(UUID userId) {
        List<CloudStorageConnectionStatus> result = new ArrayList<>();
        for (CloudStorageProvider p : providers) {
            if (!isProviderEnabled(p.getProviderId())) continue;
            boolean connected = connectionRepository.existsByUserIdAndProvider(userId, p.getProviderId());
            result.add(CloudStorageConnectionStatus.builder()
                    .provider(p.getProviderId())
                    .displayName(p.getDisplayName())
                    .connected(connected)
                    .build());
        }
        return result;
    }

    public String getAuthorizationUrl(String providerId, UUID userId) {
        CloudStorageProvider provider = getProvider(providerId);
        if (provider == null) throw new IllegalArgumentException("Unknown provider: " + providerId);
        String redirectUri = properties.getBackendUrl() + "/api/v1/cloud-storage/callback";
        String state = userId.toString() + "|" + providerId;
        return provider.buildAuthorizationUrl(redirectUri, state);
    }

    @Transactional
    public void handleOAuthCallback(String code, String state) {
        String[] parts = state.split("\\|");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid state");
        UUID userId = UUID.fromString(parts[0]);
        String providerId = parts[1];
        CloudStorageProvider provider = getProvider(providerId);
        if (provider == null) throw new IllegalArgumentException("Unknown provider: " + providerId);

        String redirectUri = properties.getBackendUrl() + "/api/v1/cloud-storage/callback";
        TokenResponse tr = provider.exchangeCodeForTokens(code, redirectUri);
        if (tr == null) throw new RuntimeException("Failed to exchange code for tokens");

        CloudStorageConnection conn = connectionRepository.findByUserIdAndProvider(userId, providerId)
                .orElse(CloudStorageConnection.builder()
                        .userId(userId)
                        .provider(providerId)
                        .build());
        conn.setAccessToken(tr.getAccessToken());
        conn.setRefreshToken(tr.getRefreshToken());
        conn.setTokenExpiresAt(tr.getTokenExpiresAt());
        conn.setCreatedAt(conn.getCreatedAt() != null ? conn.getCreatedAt() : Instant.now());
        connectionRepository.save(conn);
        log.info("Connected {} for user {}", providerId, userId);
    }

    public List<CloudFileItem> listFiles(UUID userId, String providerId, String folderId) {
        String accessToken = ensureValidToken(userId, providerId);
        CloudStorageProvider provider = getProvider(providerId);
        if (provider == null) throw new IllegalArgumentException("Unknown provider: " + providerId);
        return provider.listFiles(accessToken, folderId);
    }

    public DocumentMetadata importFile(
            UUID userId, String providerId, String fileId, String fileName,
            String jurisdiction, Integer year, boolean confidential,
            String documentType, String practiceArea, String clientName, String matterId,
            String username
    ) {
        String accessToken = ensureValidToken(userId, providerId);
        CloudStorageProvider provider = getProvider(providerId);
        if (provider == null) throw new IllegalArgumentException("Unknown provider: " + providerId);

        byte[] bytes = provider.downloadFile(accessToken, fileId);
        if (bytes == null || bytes.length == 0) throw new RuntimeException("Failed to download file");

        if (fileName == null || fileName.isBlank()) {
            fileName = fileId.contains("/") ? fileId.substring(fileId.lastIndexOf('/') + 1) : "document";
        }
        String contentType = inferContentType(fileName);

        return documentService.ingestFromBytes(
                bytes, fileName, contentType,
                jurisdiction, year, confidential,
                documentType, practiceArea, clientName, matterId,
                username
        );
    }

    public String saveToCloud(UUID userId, String providerId, String folderId, String fileName, byte[] content, String mimeType) {
        String accessToken = ensureValidToken(userId, providerId);
        CloudStorageProvider provider = getProvider(providerId);
        if (provider == null) throw new IllegalArgumentException("Unknown provider: " + providerId);
        return provider.uploadFile(accessToken, folderId, fileName, content, mimeType);
    }

    @Transactional
    public void disconnect(UUID userId, String providerId) {
        connectionRepository.deleteByUserIdAndProvider(userId, providerId);
        log.info("Disconnected {} for user {}", providerId, userId);
    }

    private String ensureValidToken(UUID userId, String providerId) {
        CloudStorageConnection conn = connectionRepository.findByUserIdAndProvider(userId, providerId)
                .orElseThrow(() -> new IllegalStateException("Not connected to " + providerId));
        if (conn.getTokenExpiresAt() != null && conn.getTokenExpiresAt().isBefore(Instant.now().plusSeconds(60))) {
            if (conn.getRefreshToken() != null) {
                CloudStorageProvider provider = getProvider(providerId);
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

    private CloudStorageProvider getProvider(String providerId) {
        return providers.stream()
                .filter(p -> p.getProviderId().equals(providerId))
                .findFirst()
                .orElse(null);
    }

    private boolean isProviderEnabled(String providerId) {
        return switch (providerId) {
            case "GOOGLE_DRIVE" -> properties.getGoogle().isEnabled();
            case "ONEDRIVE" -> properties.getMicrosoft().isEnabled();
            case "DROPBOX" -> properties.getDropbox().isEnabled();
            default -> false;
        };
    }

    private String inferContentType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }
}
