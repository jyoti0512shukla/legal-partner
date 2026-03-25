package com.legalpartner.repository;

import com.legalpartner.model.entity.AuthConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AuthConfigRepository extends JpaRepository<AuthConfigEntity, UUID> {}
