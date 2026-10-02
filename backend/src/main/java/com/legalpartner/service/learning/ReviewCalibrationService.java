package com.legalpartner.service.learning;

import com.legalpartner.config.PromptRepository;
import com.legalpartner.model.entity.learning.QuestionCalibration;
import com.legalpartner.model.entity.learning.ReviewDispute;
import com.legalpartner.repository.learning.QuestionCalibrationRepository;
import com.legalpartner.repository.learning.ReviewDisputeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Review calibration against lawyer corrections (EvalGen / Align-Evals pattern).
 *
 * Every completed review counts one answer per question; every lawyer dispute counts one error.
 * Precision gets a Beta prior so a single dispute doesn't swing weights; the question's weight in
 * the risk score is multiplied by posterior / prior precision (clamped). The latest disputes are
 * also shown to the model as few-shot corrections for that question.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewCalibrationService {

    public static final String ALL = "_ALL";

    private final QuestionCalibrationRepository calibration;
    private final ReviewDisputeRepository disputes;
    private final com.legalpartner.repository.learning.ReviewAnswerLogRepository answerLog;
    private final LearningConfig config;
    private final PromptRepository prompts;

    /**
     * Count a review's answers. Each (document, question) counts once — re-running the review of
     * the same document adds no evidence and would make precision look better than it is.
     */
    @Transactional
    public void recordAnswers(UUID documentId, String contractType, Collection<String> questionIds) {
        if (!config.isEnabled() || documentId == null || questionIds == null) return;
        for (String q : new java.util.LinkedHashSet<>(questionIds)) {
            if (answerLog.existsByDocumentIdAndQuestionId(documentId, q)) continue;
            answerLog.save(com.legalpartner.model.entity.learning.ReviewAnswerLog.builder()
                    .documentId(documentId).questionId(q).contractType(contractType).build());
            bump(q, ALL, 1, 0);
            if (contractType != null) bump(q, contractType, 1, 0);
        }
    }

    public record DisputeRequest(String questionId, String clauseType, String modelAnswer, String correctAnswer,
                                 String quote, String note) {}

    @Transactional
    public ReviewDispute dispute(UUID documentId, String contractType, DisputeRequest req, String user) {
        if (req.questionId() == null || req.questionId().isBlank()) throw new IllegalArgumentException("questionId is required");
        String correct = req.correctAnswer() == null ? "" : req.correctAnswer().trim().toUpperCase();
        if (!correct.equals("YES") && !correct.equals("NO")) throw new IllegalArgumentException("correctAnswer must be YES or NO");
        // One dispute per (document, question): a repeat correction updates it without counting twice.
        var existing = disputes.findFirstByDocumentIdAndQuestionId(documentId, req.questionId());
        ReviewDispute d = existing.orElseGet(() -> ReviewDispute.builder().documentId(documentId)
                .questionId(req.questionId()).contractType(contractType).build());
        d.setClauseType(req.clauseType());
        d.setModelAnswer(req.modelAnswer());
        d.setCorrectAnswer(correct);
        d.setQuote(req.quote());
        d.setNote(req.note());
        d.setDisputedBy(user);
        d.setCreatedAt(Instant.now());
        d = disputes.save(d);
        if (existing.isEmpty()) {
            bump(req.questionId(), ALL, 0, 1);
            if (contractType != null) bump(req.questionId(), contractType, 0, 1);
        }
        log.info("Review dispute on {} [{}]: model {} → lawyer {}", documentId, req.questionId(), req.modelAnswer(), correct);
        return d;
    }

    private void bump(String questionId, String contractType, int answered, int disputed) {
        QuestionCalibration c = calibration.findByQuestionIdAndContractType(questionId, contractType)
                .orElseGet(() -> QuestionCalibration.builder().questionId(questionId).contractType(contractType).build());
        c.setAnswered(Math.max(0, c.getAnswered() + answered));
        c.setDisputed(Math.max(0, c.getDisputed() + disputed));
        c.setUpdatedAt(Instant.now());
        calibration.save(c);
    }

    /** Weight multipliers for a contract type (contract-specific counts when present, else firm-wide). */
    public Map<String, Double> multipliers(String contractType) {
        Map<String, Double> out = new HashMap<>();
        if (!config.isEnabled()) return out;
        for (QuestionCalibration c : calibration.findByContractType(ALL)) out.put(c.getQuestionId(), multiplier(c));
        if (contractType != null) {
            for (QuestionCalibration c : calibration.findByContractType(contractType)) {
                if (c.getAnswered() >= config.getCalibrationContractTypeMinAnswered()) out.put(c.getQuestionId(), multiplier(c));
            }
        }
        return out;
    }

    double multiplier(QuestionCalibration c) {
        return multiplier(c.getAnswered(), c.getDisputed(), config.getCalibrationPriorCorrect(),
                config.getCalibrationPriorWrong(), config.getCalibrationMinMultiplier());
    }

    /** posterior precision / prior precision, Beta(a, b) prior, clamped to [min, 1]. */
    static double multiplier(int answered, int disputed, double a, double b, double min) {
        double prior = a / (a + b);
        int correct = Math.max(0, answered - disputed);
        double posterior = (a + correct) / (a + b + Math.max(answered, disputed));
        return Math.max(min, Math.min(1.0, posterior / prior));
    }

    /** Few-shot block of this firm's latest corrections for one question, or "". */
    public String correctionsFor(String questionId) {
        if (!config.isEnabled()) return "";
        int k = config.getCalibrationFewShot();
        if (k <= 0) return "";
        List<ReviewDispute> latest = disputes.findByQuestionIdOrderByCreatedAtDesc(questionId, PageRequest.of(0, k));
        if (latest.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(prompts.get("REVIEW_CORRECTIONS_HEADER"));
        String line = prompts.get("REVIEW_CORRECTION_LINE");
        for (ReviewDispute d : latest) {
            String quote = d.getQuote() == null || d.getQuote().isBlank() ? "" : " for text: \"" + truncate(d.getQuote(), 200) + "\"";
            String note = d.getNote() == null || d.getNote().isBlank() ? "" : " (" + truncate(d.getNote(), 150) + ")";
            sb.append(String.format(line, "YES".equals(d.getCorrectAnswer()) ? "PRESENT" : "ABSENT", quote, note));
        }
        return sb.toString();
    }

    public record QuestionHealth(String questionId, int answered, int disputed, double precision) {}

    /** Questions lawyers keep correcting (learning.yml calibration.needs_rewrite). */
    public List<QuestionHealth> needsRewrite() {
        return calibration.findByContractType(ALL).stream()
                .filter(c -> c.getAnswered() >= config.getNeedsRewriteMinAnswered())
                .map(c -> new QuestionHealth(c.getQuestionId(), c.getAnswered(), c.getDisputed(),
                        1.0 - (double) c.getDisputed() / Math.max(1, c.getAnswered())))
                .filter(h -> h.precision() < config.getNeedsRewriteMaxPrecision())
                .toList();
    }

    public long totalAnswered() {
        return calibration.findByContractType(ALL).stream().mapToLong(QuestionCalibration::getAnswered).sum();
    }

    public long totalDisputed() {
        return calibration.findByContractType(ALL).stream().mapToLong(QuestionCalibration::getDisputed).sum();
    }

    @org.springframework.transaction.annotation.Transactional
    public void forgetDocument(UUID documentId) {
        for (var a : answerLog.findByDocumentId(documentId)) {
            bump(a.getQuestionId(), ALL, -1, 0);
            if (a.getContractType() != null) bump(a.getQuestionId(), a.getContractType(), -1, 0);
        }
        for (ReviewDispute d : disputes.findByDocumentId(documentId)) {
            bump(d.getQuestionId(), ALL, 0, -1);
            if (d.getContractType() != null) bump(d.getQuestionId(), d.getContractType(), 0, -1);
        }
        answerLog.deleteByDocumentId(documentId);
        disputes.deleteByDocumentId(documentId);
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
