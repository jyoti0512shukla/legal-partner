package com.legalpartner.repository;

import com.legalpartner.model.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface TeamRepository extends JpaRepository<Team, UUID> {
    List<Team> findAllByOrderByNameAsc();
    Optional<Team> findByName(String name);
}
