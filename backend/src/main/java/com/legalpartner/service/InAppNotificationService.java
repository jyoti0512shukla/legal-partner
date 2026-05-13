package com.legalpartner.service;

import com.legalpartner.model.entity.Notification;
import com.legalpartner.repository.NotificationRepository;
import com.legalpartner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InAppNotificationService {

    private final NotificationRepository notificationRepo;
    private final UserRepository userRepo;

    public void notify(String userEmail, String type, String title, String message, String link) {
        Notification n = Notification.builder()
                .userEmail(userEmail)
                .type(type)
                .title(title)
                .message(message)
                .link(link)
                .build();
        notificationRepo.save(n);
        log.debug("Notification created for {}: {}", userEmail, title);
    }

    /** Notify all partners and admins */
    public void notifyPartnersAndAdmins(String type, String title, String message, String link) {
        userRepo.findAll().stream()
                .filter(u -> u.getRole().name().equals("ADMIN") || u.getRole().name().equals("PARTNER"))
                .forEach(u -> notify(u.getEmail(), type, title, message, link));
    }

    /** Notify all users in the system */
    public void notifyAll(String type, String title, String message, String link) {
        userRepo.findAll().forEach(u -> notify(u.getEmail(), type, title, message, link));
    }

    public List<Notification> getNotifications(String userEmail) {
        return notificationRepo.findTop30ByUserEmailOrderByCreatedAtDesc(userEmail);
    }

    public long getUnreadCount(String userEmail) {
        return notificationRepo.countByUserEmailAndReadFalse(userEmail);
    }

    public void markRead(UUID notificationId) {
        notificationRepo.markRead(notificationId);
    }

    public void markAllRead(String userEmail) {
        notificationRepo.markAllReadByUserEmail(userEmail);
    }
}
