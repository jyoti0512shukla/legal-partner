package com.legalpartner.repository;

import com.legalpartner.model.entity.DeadlineAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DeadlineAlertRepository extends JpaRepository<DeadlineAlert, UUID> {

    List<DeadlineAlert> findBySentFalseAndAlertDate(LocalDate date);

    List<DeadlineAlert> findByDeadlineId(UUID deadlineId);

    @Transactional
    void deleteByDeadlineId(UUID deadlineId);
}
