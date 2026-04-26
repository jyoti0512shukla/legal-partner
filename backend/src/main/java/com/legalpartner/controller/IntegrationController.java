package com.legalpartner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.IntegrationProperties;
import com.legalpartner.integration.DocuSignProvider;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.IntegrationConnection;
import com.legalpartner.rag.DocumentFullTextRetriever;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.UserRepository;
import com.legalpartner.service.IntegrationService;
import com.legalpartner.service.IntegrationService.IntegrationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService integrationService;
    private final IntegrationProperties integrationProperties;
    private final UserRepository userRepository;
    private final DocuSignProvider docuSignProvider;
    private final DocumentMetadataRepository documentRepository;
    private final DocumentFullTextRetriever fullTextRetriever;
    private final ObjectMapper objectMapper;

    @GetMapping("/connections")
    public List<IntegrationStatus> getConnections(Authentication auth) {
        UUID userId = getUserId(auth);
        return integrationService.getConnectionStatuses(userId);
    }

    @GetMapping("/auth-url")
    public AuthUrlResponse getAuthUrl(
            @RequestParam String provider,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        String url = integrationService.getAuthorizationUrl(provider, userId);
        return new AuthUrlResponse(url);
    }

    @GetMapping("/callback")
    public RedirectView handleCallback(
            @RequestParam String code,
            @RequestParam String state
    ) {
        integrationService.handleOAuthCallback(code, state);
        String providerId = state.contains("::") ? state.split("::")[1] : "";
        return new RedirectView(integrationProperties.getFrontendUrl() + "/settings?tab=integrations&connected=" + providerId);
    }

    @PostMapping("/slack/configure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void configureSlack(
            @RequestBody SlackConfigRequest request,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        integrationService.saveWebhookConfig(userId, "SLACK", request.webhookUrl());
    }

    @PostMapping("/teams/configure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void configureTeams(
            @RequestBody TeamsConfigRequest request,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        integrationService.saveWebhookConfig(userId, "MICROSOFT_TEAMS", request.webhookUrl());
    }

    @PostMapping("/docusign/send/{docId}")
    public Map<String, String> sendForSignature(
            @PathVariable UUID docId,
            @RequestBody SendForSignatureRequest request,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        String accessToken = integrationService.ensureValidToken(userId, "DOCUSIGN");
        IntegrationConnection conn = integrationService.getConnection(userId, "DOCUSIGN");

        // Parse accountId and baseUri from connection config
        String accountId;
        String baseUri;
        try {
            var node = objectMapper.readTree(conn.getConfig());
            accountId = node.path("accountId").asText();
            baseUri = node.path("baseUri").asText();
        } catch (Exception e) {
            throw new IllegalStateException("DocuSign connection config is invalid — try reconnecting");
        }
        if (accountId.isBlank() || baseUri.isBlank()) {
            throw new IllegalStateException("DocuSign accountId or baseUri missing — try reconnecting");
        }

        // Get document content as base64
        DocumentMetadata doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        String htmlContent = fullTextRetriever.retrieveFullText(docId);
        if (htmlContent.isBlank()) {
            throw new IllegalArgumentException("Document has no content — wait for indexing to complete");
        }
        String documentBase64 = Base64.getEncoder().encodeToString(htmlContent.getBytes());
        String docName = doc.getFileName() != null ? doc.getFileName() : "Contract.html";

        String envelopeId = docuSignProvider.sendEnvelope(
                accessToken, accountId, baseUri,
                documentBase64, docName,
                request.signerEmail(), request.signerName(),
                request.emailSubject() != null ? request.emailSubject() : "Please sign: " + docName
        );

        return Map.of("envelopeId", envelopeId, "status", "sent");
    }

    @DeleteMapping("/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(
            @RequestParam String provider,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        integrationService.disconnect(userId, provider);
    }

    private UUID getUserId(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"))
                .getId();
    }

    public record AuthUrlResponse(String url) {}
    public record SlackConfigRequest(String webhookUrl) {}
    public record TeamsConfigRequest(String webhookUrl) {}
    public record SendForSignatureRequest(String signerEmail, String signerName, String emailSubject) {}
}
