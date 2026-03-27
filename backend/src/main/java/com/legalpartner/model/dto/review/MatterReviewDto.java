package com.legalpartner.model.dto.review;
import java.time.Instant;
import java.util.UUID;
public record MatterReviewDto(UUID id, UUID matterId, String matterName, UUID documentId, 
                               String documentName, String pipelineName, String currentStageName,
                               int currentStageOrder, int totalStages, String status,
                               String requiredRole, String availableActions,
                               UUID startedBy, Instant startedAt, Instant completedAt) {}
