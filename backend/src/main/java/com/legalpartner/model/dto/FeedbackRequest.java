package com.legalpartner.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record FeedbackRequest(
        String conversationId,
        String queryText,
        String answerText,
        @Min(1) @Max(5) Integer rating,
        Boolean isCorrect,
        String correctedAnswer,
        String feedbackNote,
        String matterId
) {}
