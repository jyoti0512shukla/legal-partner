package com.legalpartner.controller;

import com.legalpartner.model.dto.*;
import com.legalpartner.service.AiService;
import com.legalpartner.service.DraftService;
import com.legalpartner.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final DraftService draftService;
    private final TemplateService templateService;

    @GetMapping("/templates")
    public List<TemplateInfo> listTemplates() {
        return templateService.listTemplates();
    }

    @PostMapping("/draft")
    public DraftResponse draft(@Valid @RequestBody DraftRequest request, Authentication auth) {
        return draftService.generateDraft(request, auth.getName());
    }

    @PostMapping(value = "/draft/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDraft(@Valid @RequestBody DraftRequest request, Authentication auth) {
        return draftService.streamDraft(request, auth.getName());
    }

    @PostMapping("/query")
    public QueryResult query(@Valid @RequestBody QueryRequest request, Authentication auth) {
        return aiService.query(request, auth.getName());
    }

    @PostMapping("/compare")
    public CompareResult compare(@Valid @RequestBody CompareRequest request, Authentication auth) {
        return aiService.compare(request, auth.getName());
    }

    @GetMapping("/risk-assessment/{docId}")
    public ResponseEntity<RiskAssessmentResult> getCachedRiskAssessment(@PathVariable UUID docId) {
        return aiService.getCachedRiskAssessment(docId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/risk-assessment/{docId}")
    public RiskAssessmentResult riskAssessment(@PathVariable UUID docId, Authentication auth) {
        return aiService.assessRisk(docId, auth.getName());
    }

    @PostMapping("/extract/{docId}")
    public ExtractionResult extract(@PathVariable UUID docId, Authentication auth) {
        return aiService.extractKeyTerms(docId, auth.getName());
    }

    @PostMapping("/refine-clause")
    public RefineClauseResponse refineClause(@Valid @RequestBody RefineClauseRequest request, Authentication auth) {
        return aiService.refineClause(request, auth.getName());
    }
}
