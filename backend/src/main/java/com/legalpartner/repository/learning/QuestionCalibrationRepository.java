package com.legalpartner.repository.learning;

import com.legalpartner.model.entity.learning.QuestionCalibration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuestionCalibrationRepository extends JpaRepository<QuestionCalibration, UUID> {
    Optional<QuestionCalibration> findByQuestionIdAndContractType(String questionId, String contractType);

    List<QuestionCalibration> findByContractType(String contractType);
}
