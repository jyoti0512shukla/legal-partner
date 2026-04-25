package com.legalpartner.controller;

import com.legalpartner.model.dto.ContractReviewRequest;
import com.legalpartner.model.dto.ContractReviewResult;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.service.ContractReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/review")
@RequiredArgsConstructor
public class ContractReviewController {

    private final ContractReviewService contractReviewService;
    private final DocumentMetadataRepository documentRepository;

    @PostMapping
    public java.util.Map<String, Object> review(
            @Valid @RequestBody ContractReviewRequest request,
            @RequestParam(value = "regenerate", defaultValue = "false") boolean regenerate,
            Authentication auth) {
        DocumentMetadata doc = documentRepository.findById(request.documentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        boolean wasCached = !regenerate && doc.getExtractionJson() != null && !doc.getExtractionJson().isBlank();

        ContractReviewResult result = contractReviewService.review(request, auth.getName(), regenerate);

        if (!wasCached) {
            doc = documentRepository.findById(request.documentId()).orElse(doc);
        }

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("documentName", result.documentName());
        out.put("overallRisk", result.overallRisk());
        out.put("clausesPresent", result.clausesPresent());
        out.put("clausesMissing", result.clausesMissing());
        out.put("clausesWeak", result.clausesWeak());
        out.put("clauses", result.clauses());
        out.put("criticalMissingClauses", result.criticalMissingClauses());
        out.put("recommendations", result.recommendations());
        out.put("cached", wasCached);
        out.put("generatedAt", doc.getExtractionAt() != null ? doc.getExtractionAt().toString() : null);
        return out;
    }
}
