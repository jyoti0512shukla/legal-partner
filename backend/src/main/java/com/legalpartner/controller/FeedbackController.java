package com.legalpartner.controller;

import com.legalpartner.model.dto.FeedbackRequest;
import com.legalpartner.model.entity.QueryFeedback;
import com.legalpartner.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QueryFeedback submit(
            @Valid @RequestBody FeedbackRequest request,
            Authentication auth) {
        return feedbackService.saveFeedback(request, auth.getName());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "averageRating", feedbackService.getAverageRating(),
                "incorrectAnswers", feedbackService.getIncorrectCount()
        );
    }
}
