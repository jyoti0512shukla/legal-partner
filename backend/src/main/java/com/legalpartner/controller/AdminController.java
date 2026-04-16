package com.legalpartner.controller;

import com.legalpartner.service.DocumentReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin-only operations. Currently just the anonymization re-index endpoint,
 * but this is where other one-shot maintenance ops should live.
 *
 * Access: ADMIN role only (enforced via @PreAuthorize — SecurityConfig's
 * path-matcher for /api/v1/admin/** is the coarse-grained backstop).
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DocumentReindexService reindexService;

    /**
     * Re-run the anonymization pipeline over every non-anonymized document.
     * Async — returns the count scheduled; check progress via
     * {@code /api/v1/documents} (look at the is_anonymized flag).
     *
     * Safe to call multiple times; only touches docs where is_anonymized=false
     * AND the raw file is still on disk.
     */
    @PostMapping("/reindex-anonymization")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reindexAnonymization(Authentication auth) {
        int count = reindexService.reindexAllNonAnonymized();
        return Map.of("scheduled", count,
                      "note", "Processing runs async. Check document_metadata.is_anonymized for progress.");
    }
}
