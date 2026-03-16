package com.legalpartner.controller;

import com.legalpartner.config.CloudStorageProperties;
import com.legalpartner.model.dto.cloud.CloudFileItem;
import com.legalpartner.model.dto.cloud.CloudStorageConnectionStatus;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.repository.UserRepository;
import com.legalpartner.service.CloudStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cloud-storage")
@RequiredArgsConstructor
public class CloudStorageController {

    private final CloudStorageService cloudStorageService;
    private final CloudStorageProperties cloudProperties;
    private final UserRepository userRepository;

    @GetMapping("/connections")
    public List<CloudStorageConnectionStatus> getConnections(Authentication auth) {
        UUID userId = getUserId(auth);
        return cloudStorageService.getConnectionStatuses(userId);
    }

    @GetMapping("/auth-url")
    public AuthUrlResponse getAuthUrl(
            @RequestParam String provider,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        String url = cloudStorageService.getAuthorizationUrl(provider, userId);
        return new AuthUrlResponse(url);
    }

    @GetMapping("/callback")
    public RedirectView handleCallback(
            @RequestParam String code,
            @RequestParam String state
    ) {
        cloudStorageService.handleOAuthCallback(code, state);
        return new RedirectView(cloudProperties.getFrontendUrl() + "/documents?cloud=connected");
    }

    @GetMapping("/files")
    public List<CloudFileItem> listFiles(
            @RequestParam String provider,
            @RequestParam(required = false) String folderId,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        return cloudStorageService.listFiles(userId, provider, folderId);
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentMetadata importFile(
            @RequestBody ImportRequest request,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        return cloudStorageService.importFile(
                userId, request.provider(), request.fileId(), request.fileName(),
                request.jurisdiction(), request.year(), request.confidential(),
                request.documentType(), request.practiceArea(), request.clientName(), request.matterId(),
                auth.getName()
        );
    }

    @PostMapping("/save")
    public SaveResponse saveToCloud(
            @RequestBody SaveRequest request,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        byte[] content = java.util.Base64.getDecoder().decode(request.content());
        String mimeType = request.mimeType() != null ? request.mimeType() : "text/html";
        String fileId = cloudStorageService.saveToCloud(
                userId, request.provider(), request.folderId(), request.fileName(), content, mimeType);
        return new SaveResponse(fileId, "Saved successfully");
    }

    @DeleteMapping("/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(
            @RequestParam String provider,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        cloudStorageService.disconnect(userId, provider);
    }

    private UUID getUserId(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"))
                .getId();
    }

    public record AuthUrlResponse(String url) {}
    public record SaveRequest(String provider, String folderId, String fileName, String content, String mimeType) {}
    public record SaveResponse(String fileId, String message) {}
    public record ImportRequest(
            String provider,
            String fileId,
            String fileName,
            String jurisdiction,
            Integer year,
            boolean confidential,
            String documentType,
            String practiceArea,
            String clientName,
            String matterId
    ) {}
}
