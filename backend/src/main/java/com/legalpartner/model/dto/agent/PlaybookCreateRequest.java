package com.legalpartner.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record PlaybookCreateRequest(@NotBlank String name, @NotBlank String dealType,
                                    String description, boolean isDefault,
                                    List<PlaybookPositionDto> positions) {}
