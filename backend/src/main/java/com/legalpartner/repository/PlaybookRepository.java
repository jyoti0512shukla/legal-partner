package com.legalpartner.repository;

import com.legalpartner.model.entity.Playbook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaybookRepository extends JpaRepository<Playbook, UUID> {
    List<Playbook> findByDealType(String dealType);
    Optional<Playbook> findByDealTypeAndIsDefaultTrue(String dealType);
    List<Playbook> findAllByOrderByCreatedAtDesc();
}
