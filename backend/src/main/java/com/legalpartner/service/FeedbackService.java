package com.legalpartner.service;

import com.legalpartner.model.dto.FeedbackRequest;
import com.legalpartner.model.entity.QueryFeedback;
import com.legalpartner.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;

    public QueryFeedback saveFeedback(FeedbackRequest request, String username) {
        UUID matterId = null;
        if (request.matterId() != null && !request.matterId().isBlank()) {
            try { matterId = UUID.fromString(request.matterId()); } catch (Exception ignored) {}
        }
        QueryFeedback feedback = QueryFeedback.builder()
                .conversationId(request.conversationId())
                .username(username)
                .queryText(request.queryText() != null ? request.queryText() : "")
                .answerText(request.answerText())
                .rating(request.rating())
                .isCorrect(request.isCorrect())
                .correctedAnswer(request.correctedAnswer())
                .feedbackNote(request.feedbackNote())
                .matterId(matterId)
                .build();
        feedback = feedbackRepository.save(feedback);
        log.info("Feedback saved: rating={} correct={} by {}", request.rating(), request.isCorrect(), username);
        return feedback;
    }

    public Double getAverageRating() {
        return feedbackRepository.averageRating();
    }

    public long getIncorrectCount() {
        return feedbackRepository.countIncorrectAnswers();
    }
}
