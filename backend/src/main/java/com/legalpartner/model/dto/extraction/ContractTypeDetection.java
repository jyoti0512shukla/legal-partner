package com.legalpartner.model.dto.extraction;

import java.util.List;

public record ContractTypeDetection(
        String contractType,
        double confidence,
        List<String> signals
) {
    public boolean isHighConfidence() { return confidence >= 0.7; }
    public boolean isLowConfidence() { return confidence < 0.5; }
}
