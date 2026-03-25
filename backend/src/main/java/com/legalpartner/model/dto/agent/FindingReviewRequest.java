package com.legalpartner.model.dto.agent;

import jakarta.validation.constraints.NotBlank;

public record FindingReviewRequest(@NotBlank String status) {}
