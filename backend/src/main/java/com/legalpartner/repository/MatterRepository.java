package com.legalpartner.repository;

import com.legalpartner.model.entity.Matter;
import com.legalpartner.model.enums.MatterStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MatterRepository extends JpaRepository<Matter, UUID> {
    List<Matter> findByStatusOrderByCreatedAtDesc(MatterStatus status);
    List<Matter> findByClientNameContainingIgnoreCaseOrderByCreatedAtDesc(String clientName);
    Optional<Matter> findByMatterRef(String matterRef);
    List<Matter> findAllByOrderByCreatedAtDesc();
}
