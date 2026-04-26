package com.legalpartner.repository;

import com.legalpartner.model.entity.SignatureEnvelope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SignatureEnvelopeRepository extends JpaRepository<SignatureEnvelope, UUID> {
    Optional<SignatureEnvelope> findByEnvelopeId(String envelopeId);
    List<SignatureEnvelope> findByDocumentIdOrderBySentAtDesc(UUID documentId);
    List<SignatureEnvelope> findByMatterIdOrderBySentAtDesc(UUID matterId);
    List<SignatureEnvelope> findBySentByOrderBySentAtDesc(UUID sentBy);
}
