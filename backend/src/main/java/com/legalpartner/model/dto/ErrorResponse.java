package com.legalpartner.model.dto;

import java.time.Instant;

public record ErrorResponse(String error, String message, Instant timestamp) {}
