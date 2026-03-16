package com.legalpartner.storage;

import com.legalpartner.model.dto.cloud.CloudFileItem;

import java.util.List;

/**
 * Common interface for cloud storage providers (Google Drive, OneDrive, Dropbox).
 */
public interface CloudStorageProvider {

    String getProviderId();

    String getDisplayName();

    String buildAuthorizationUrl(String redirectUri, String state);

    TokenResponse exchangeCodeForTokens(String code, String redirectUri);

    String refreshAccessToken(String refreshToken);

    List<CloudFileItem> listFiles(String accessToken, String folderId);

    byte[] downloadFile(String accessToken, String fileId);

    /**
     * Upload a file to the given folder. Returns the created file ID or path.
     */
    String uploadFile(String accessToken, String folderId, String fileName, byte[] content, String mimeType);
}
