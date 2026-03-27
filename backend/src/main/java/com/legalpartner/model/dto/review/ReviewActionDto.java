package com.legalpartner.model.dto.review;
import java.time.Instant;
import java.util.UUID;
public record ReviewActionDto(UUID id, String stageName, String action, 
                               String actedByName, String notes, Instant createdAt) {}
