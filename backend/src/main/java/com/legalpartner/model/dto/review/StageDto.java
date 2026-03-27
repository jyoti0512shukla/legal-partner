package com.legalpartner.model.dto.review;
import java.util.UUID;
public record StageDto(UUID id, int stageOrder, String name, String requiredRole, 
                       String actions, boolean autoNotify) {}
