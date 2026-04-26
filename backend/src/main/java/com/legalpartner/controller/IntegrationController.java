package com.legalpartner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.config.IntegrationProperties;
import com.legalpartner.integration.DocuSignProvider;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.IntegrationConnection;
import com.legalpartner.model.entity.SignatureEnvelope;
import com.legalpartner.rag.DocumentFullTextRetriever;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.SignatureEnvelopeRepository;
import com.legalpartner.repository.UserRepository;
import com.legalpartner.service.IntegrationService;
import com.legalpartner.service.IntegrationService.IntegrationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class IntegrationController {

    private final IntegrationService integrationService;
    private final IntegrationProperties integrationProperties;
    private final UserRepository userRepository;
    private final DocuSignProvider docuSignProvider;
    private final DocumentMetadataRepository documentRepository;
    private final DocumentFullTextRetriever fullTextRetriever;
    private final SignatureEnvelopeRepository envelopeRepository;
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
    public Map<String, Object> sendForSignature(
            @PathVariable UUID docId,
            @RequestBody SendForSignatureRequest request,
            Authentication auth
    ) {
        UUID userId = getUserId(auth);
        String accessToken = integrationService.ensureValidToken(userId, "DOCUSIGN");
        IntegrationConnection conn = integrationService.getConnection(userId, "DOCUSIGN");

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

        // Get document content
        DocumentMetadata doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        String htmlContent = fullTextRetriever.retrieveFullText(docId);
        if (htmlContent.isBlank()) {
            throw new IllegalArgumentException("Document has no content — wait for indexing to complete");
        }
        String documentBase64 = Base64.getEncoder().encodeToString(htmlContent.getBytes());
        String docName = doc.getFileName() != null ? doc.getFileName() : "Contract.html";
        String subject = request.emailSubject() != null ? request.emailSubject() : "Please sign: " + docName;

        // Build multi-recipient envelope
        String envelopeId = docuSignProvider.sendMultiRecipientEnvelope(
                accessToken, accountId, baseUri,
                documentBase64, docName, subject,
                request.recipients()
        );

        // Track the envelope
        String recipientsJson;
        try { recipientsJson = objectMapper.writeValueAsString(request.recipients()); }
        catch (Exception e) { recipientsJson = "[]"; }

        SignatureEnvelope envelope = SignatureEnvelope.builder()
                .envelopeId(envelopeId)
                .documentId(docId)
                .matterId(request.matterId())
                .sentBy(userId)
                .status("sent")
                .recipients(recipientsJson)
                .emailSubject(subject)
                .build();
        envelopeRepository.save(envelope);
        log.info("Sent DocuSign envelope {} for doc {} with {} recipients", envelopeId, docId, request.recipients().size());

        return Map.of("envelopeId", envelopeId, "status", "sent", "recipientCount", request.recipients().size());
    }

    @GetMapping("/docusign/envelopes/{docId}")
    public List<SignatureEnvelope> getEnvelopes(@PathVariable UUID docId) {
        return envelopeRepository.findByDocumentIdOrderBySentAtDesc(docId);
    }

    /** DocuSign Connect webhook — receives envelope status updates */
    @PostMapping("/docusign/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void docuSignWebhook(@RequestBody String payload) {
        try {
            var node = objectMapper.readTree(payload);
            String envelopeId = node.path("envelopeId").asText(node.path("EnvelopeID").asText(""));
            String status = node.path("status").asText(node.path("Status").asText(""));
            if (envelopeId.isBlank()) {
                log.warn("DocuSign webhook: no envelopeId in payload");
                return;
            }
            log.info("DocuSign webhook: envelope {} status → {}", envelopeId, status);

            envelopeRepository.findByEnvelopeId(envelopeId).ifPresent(envelope -> {
                envelope.setStatus(status.toLowerCase());
                if ("completed".equalsIgnoreCase(status)) {
                    envelope.setCompletedAt(java.time.Instant.now());
                    // Download signed PDF and store it
                    try {
                        IntegrationConnection conn = integrationService.getConnection(envelope.getSentBy(), "DOCUSIGN");
                        String accessToken = integrationService.ensureValidToken(envelope.getSentBy(), "DOCUSIGN");
                        var configNode = objectMapper.readTree(conn.getConfig());
                        String acctId = configNode.path("accountId").asText();
                        String bUri = configNode.path("baseUri").asText();
                        byte[] pdfBytes = docuSignProvider.downloadSignedDocument(accessToken, acctId, bUri, envelopeId);
                        if (pdfBytes != null && pdfBytes.length > 0) {
                            String pdfPath = "signed/" + envelopeId + ".pdf";
                            // Store in document storage volume
                            java.nio.file.Path storagePath = java.nio.file.Path.of("/data/documents", pdfPath);
                            java.nio.file.Files.createDirectories(storagePath.getParent());
                            java.nio.file.Files.write(storagePath, pdfBytes);
                            envelope.setSignedPdfPath(pdfPath);
                            log.info("Stored signed PDF for envelope {} at {}", envelopeId, pdfPath);
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to download signed PDF for envelope {}: {}", envelopeId, ex.getMessage());
                    }
                } else if ("voided".equalsIgnoreCase(status) || "declined".equalsIgnoreCase(status)) {
                    envelope.setVoidedAt(java.time.Instant.now());
                }
                envelopeRepository.save(envelope);
                log.info("Updated envelope {} → {}", envelopeId, status);
            });
        } catch (Exception e) {
            log.error("DocuSign webhook processing failed: {}", e.getMessage());
        }
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
    public record SendForSignatureRequest(
            List<SignatureRecipient> recipients,
            String emailSubject,
            UUID matterId
    ) {}
    public record SignatureRecipient(
            String email,
            String name,
            String role,       // SIGNER, REVIEWER, CC
            int routingOrder
    ) {}
}
