package com.legalpartner.model.dto.extraction;

import java.time.Instant;
import java.util.List;

public record ExtractionPipelineResult(
        List<ExtractionEntry> entries,
        ContractTypeDetection contractTypeDetection,
        List<String> consistencyIssues,
        List<RiskSummaryItem> topRisks,
        int totalFieldsDiscovered,
        int totalFieldsValidated,
        int totalGaps,
        Instant generatedAt
) {
    public record RiskSummaryItem(String risk, String severity, String explanation) {}
}
