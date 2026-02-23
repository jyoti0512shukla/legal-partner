package com.legalpartner.audit;

import com.legalpartner.model.enums.AuditActionType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {

    private final ApplicationEventPublisher eventPublisher;
    private static final String START_TIME = "audit_start_time";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) return;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return;

        Long startTime = (Long) request.getAttribute(START_TIME);
        long elapsed = startTime != null ? System.currentTimeMillis() - startTime : 0;

        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst().orElse("UNKNOWN");

        AuditActionType action = inferAction(request.getMethod(), uri);

        eventPublisher.publishEvent(AuditEvent.builder()
                .username(auth.getName())
                .userRole(role)
                .action(action)
                .endpoint(uri)
                .httpMethod(request.getMethod())
                .responseTimeMs(elapsed)
                .ipAddress(request.getRemoteAddr())
                .success(response.getStatus() < 400)
                .errorMessage(ex != null ? ex.getMessage() : null)
                .build());
    }

    private AuditActionType inferAction(String method, String uri) {
        if (uri.contains("/documents") && "POST".equals(method)) return AuditActionType.DOCUMENT_UPLOAD;
        if (uri.contains("/documents") && "DELETE".equals(method)) return AuditActionType.DOCUMENT_DELETE;
        if (uri.contains("/documents") && "GET".equals(method)) return AuditActionType.DOCUMENT_VIEW;
        if (uri.contains("/ai/query")) return AuditActionType.AI_QUERY;
        if (uri.contains("/ai/compare")) return AuditActionType.AI_COMPARE;
        if (uri.contains("/ai/risk")) return AuditActionType.RISK_ASSESSMENT;
        if (uri.contains("/audit/export")) return AuditActionType.AUDIT_EXPORT;
        if (uri.contains("/audit")) return AuditActionType.AUDIT_VIEW;
        return AuditActionType.DOCUMENT_VIEW;
    }
}
