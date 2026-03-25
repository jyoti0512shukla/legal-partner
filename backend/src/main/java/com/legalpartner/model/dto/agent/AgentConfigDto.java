package com.legalpartner.model.dto.agent;

public record AgentConfigDto(boolean autoAnalyzeOnUpload, boolean crossReferenceDocs,
                             boolean checkPlaybook, String notifyHigh, String notifyMedium,
                             String notifyLow, String quietHoursStart, String quietHoursEnd) {}
