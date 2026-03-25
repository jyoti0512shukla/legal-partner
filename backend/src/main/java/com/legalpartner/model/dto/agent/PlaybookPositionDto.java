package com.legalpartner.model.dto.agent;

import java.util.UUID;

public record PlaybookPositionDto(UUID id, String clauseType, String standardPosition,
                                  String minimumAcceptable, boolean nonNegotiable, String notes) {}
