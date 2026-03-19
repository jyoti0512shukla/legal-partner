package com.legalpartner.controller;

import com.legalpartner.model.dto.ContractReviewRequest;
import com.legalpartner.model.dto.ContractReviewResult;
import com.legalpartner.service.ContractReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/review")
@RequiredArgsConstructor
public class ContractReviewController {

    private final ContractReviewService contractReviewService;

    @GetMapping("/{docId}")
    public ResponseEntity<ContractReviewResult> getCachedReview(@PathVariable UUID docId) {
        return contractReviewService.getCachedReview(docId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping
    public ContractReviewResult review(
            @Valid @RequestBody ContractReviewRequest request,
            Authentication auth) {
        return contractReviewService.review(request, auth.getName());
    }
}
