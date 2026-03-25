package com.legalpartner.model.dto.agent;

public record FindingSummaryDto(long highCount, long mediumCount, long lowCount,
                                long unreviewedCount, long totalCount) {}
