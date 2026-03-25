package com.legalpartner.model.dto;

import jakarta.validation.constraints.NotBlank;

public record TeamCreateRequest(@NotBlank String name, String description) {}
