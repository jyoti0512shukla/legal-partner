package com.legalpartner.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutiveSummaryResult {
    private String executiveSummary;
    private String overallRisk;
    private List<String> topConcerns;
    private List<String> recommendations;
    private List<String> redFlags;
}
