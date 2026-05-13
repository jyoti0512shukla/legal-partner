package com.legalpartner.controller;

import com.legalpartner.model.entity.Notification;
import com.legalpartner.service.InAppNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final InAppNotificationService notificationService;

    @GetMapping
    public Map<String, Object> getNotifications(Authentication auth) {
        String email = auth.getName();
        return Map.of(
                "notifications", notificationService.getNotifications(email),
                "unreadCount", notificationService.getUnreadCount(email)
        );
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id) {
        notificationService.markRead(id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(Authentication auth) {
        notificationService.markAllRead(auth.getName());
    }
}
