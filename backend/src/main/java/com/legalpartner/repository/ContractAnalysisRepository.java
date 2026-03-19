package com.legalpartner.repository;

import com.legalpartner.model.entity.ContractAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ContractAnalysisRepository extends JpaRepository<ContractAnalysis, UUID> {
    Optional<ContractAnalysis> findByDocumentIdAndAnalysisType(UUID documentId, String analysisType);
}
