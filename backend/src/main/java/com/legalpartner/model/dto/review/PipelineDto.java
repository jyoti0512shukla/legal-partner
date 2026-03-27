package com.legalpartner.model.dto.review;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record PipelineDto(UUID id, String name, String description, boolean isDefault, 
                          List<StageDto> stages, Instant createdAt) {}
