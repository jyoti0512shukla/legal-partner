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
public class WorkflowAnalyticsDto {
    private long totalRuns;
    private long completedRuns;
    private long failedRuns;
    private long runningRuns;
    private double completionRate;   // 0-100
    private long avgDurationMs;
    private List<WorkflowUsageStat> byWorkflow;
    private List<DailyRunStat> byDay;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class WorkflowUsageStat {
        private String name;
        private long totalRuns;
        private long completedRuns;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DailyRunStat {
        private String date;   // "2024-01-15"
        private long count;
    }
}
