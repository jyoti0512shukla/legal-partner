package com.legalpartner.controller;

import com.legalpartner.model.dto.EdgarImportRequest;
import com.legalpartner.model.dto.EdgarSearchRequest;
import com.legalpartner.service.EdgarImportService;
import com.legalpartner.service.EdgarImportService.EdgarSearchResult;
import com.legalpartner.service.EdgarImportService.ImportResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/edgar")
@RequiredArgsConstructor
@Slf4j
public class EdgarImportController {

    private final EdgarImportService edgarImportService;

    /** Returns the list of predefined query presets for the UI */
    @GetMapping("/presets")
    public Map<String, String> presets() {
        return EdgarImportService.PRESET_QUERIES;
    }

    /** Search EDGAR for matching agreements */
    @PostMapping("/search")
    public List<EdgarSearchResult> search(@RequestBody EdgarSearchRequest req) {
        String query = req.getQuery();
        if ((query == null || query.isBlank()) && req.getPreset() != null) {
            query = EdgarImportService.PRESET_QUERIES.get(req.getPreset());
        }
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query or preset is required");
        }
        try {
            return edgarImportService.search(query, Math.min(req.getMaxResults(), 40));
        } catch (Exception e) {
            log.error("EDGAR search failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "EDGAR search failed: " + e.getMessage());
        }
    }

    /** Import selected documents into the RAG corpus */
    @PostMapping("/import")
    public List<ImportResult> importDocs(@RequestBody EdgarImportRequest req, Authentication auth) {
        if (req.getDocIds() == null || req.getDocIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No documents selected");
        }
        return edgarImportService.batchImport(
                req.getDocIds(),
                req.getDocIdToUrl(),
                req.getDocIdToEntity(),
                req.getContractType(),
                req.getIndustry(),
                req.getPracticeArea(),
                auth.getName()
        );
    }
}
