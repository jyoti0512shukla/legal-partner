package com.legalpartner.repository;

import com.legalpartner.model.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findTop30ByUserEmailOrderByCreatedAtDesc(String userEmail);

    long countByUserEmailAndReadFalse(String userEmail);

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.read = true WHERE n.userEmail = :email AND n.read = false")
    void markAllReadByUserEmail(@Param("email") String email);

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.read = true WHERE n.id = :id")
    void markRead(@Param("id") UUID id);
}
