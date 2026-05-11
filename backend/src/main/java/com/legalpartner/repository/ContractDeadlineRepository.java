package com.legalpartner.repository;

import com.legalpartner.model.entity.ContractDeadline;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ContractDeadlineRepository extends JpaRepository<ContractDeadline, UUID> {

    @Query("SELECT d FROM ContractDeadline d JOIN FETCH d.document WHERE d.document.id = :documentId")
    List<ContractDeadline> findByDocumentId(@Param("documentId") UUID documentId);

    @Query("SELECT d FROM ContractDeadline d WHERE d.actioned = false AND d.deadlineDate <= :cutoff ORDER BY d.deadlineDate ASC")
    List<ContractDeadline> findUpcomingUnactioned(@Param("cutoff") LocalDate cutoff);

    @Query("SELECT d FROM ContractDeadline d JOIN FETCH d.document doc WHERE doc.contractStatus IN ('ACTIVE', 'EXPIRING') AND d.actioned = false ORDER BY d.deadlineDate ASC")
    List<ContractDeadline> findActiveUnactioned(Pageable pageable);

    @Transactional
    void deleteByDocumentId(UUID documentId);
}
