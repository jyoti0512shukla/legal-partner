package com.legalpartner.model.dto.review;
import jakarta.validation.constraints.NotBlank;
public record ReviewActionRequest(@NotBlank String action, String notes) {}
