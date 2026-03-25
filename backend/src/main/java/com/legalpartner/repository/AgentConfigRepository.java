package com.legalpartner.repository;

import com.legalpartner.model.entity.AgentConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentConfigRepository extends JpaRepository<AgentConfig, UUID> {}
