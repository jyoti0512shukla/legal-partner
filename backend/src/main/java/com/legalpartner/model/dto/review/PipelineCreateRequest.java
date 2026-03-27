package com.legalpartner.model.dto.review;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
public record PipelineCreateRequest(@NotBlank String name, String description, 
                                     boolean isDefault, List<StageDto> stages) {}
