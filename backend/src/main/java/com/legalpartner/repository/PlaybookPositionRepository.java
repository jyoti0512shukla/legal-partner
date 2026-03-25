package com.legalpartner.repository;

import com.legalpartner.model.entity.PlaybookPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaybookPositionRepository extends JpaRepository<PlaybookPosition, UUID> {
    List<PlaybookPosition> findByPlaybookId(UUID playbookId);
}
