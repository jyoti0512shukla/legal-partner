package com.legalpartner.repository;

import com.legalpartner.model.entity.DeadlineAlertConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeadlineAlertConfigRepository extends JpaRepository<DeadlineAlertConfig, UUID> {

    List<DeadlineAlertConfig> findByEnabledTrueOrderByAlertWindowDaysDesc();
}
