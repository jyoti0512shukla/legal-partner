package com.legalpartner.controller;

import com.legalpartner.model.dto.*;
import com.legalpartner.service.AiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @PostMapping("/query")
    public QueryResult query(@Valid @RequestBody QueryRequest request, Authentication auth) {
        return aiService.query(request, auth.getName());
    }

    @PostMapping("/compare")
    public CompareResult compare(@Valid @RequestBody CompareRequest request, Authentication auth) {
        return aiService.compare(request, auth.getName());
    }

    @PostMapping("/risk-assessment/{docId}")
    public RiskAssessmentResult riskAssessment(@PathVariable UUID docId, Authentication auth) {
        return aiService.assessRisk(docId, auth.getName());
    }
}
