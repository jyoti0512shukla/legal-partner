package com.legalpartner.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting for AI endpoints and auth endpoints.
 * - AI endpoints: per authenticated user (15/min default)
 * - Auth endpoints: per IP (5 login/min, 3 register/min, 3 reset/hr)
 * IP resolution trusts only the direct connection, not X-Forwarded-For (spoofable).
 */
@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${legalpartner.rate-limit.ai-requests-per-minute:15}")
    private int aiRequestsPerMinute;

    @Value("${legalpartner.rate-limit.login-per-minute:5}")
    private int loginPerMinute;

    @Value("${legalpartner.rate-limit.register-per-minute:3}")
    private int registerPerMinute;

    @Value("${legalpartner.rate-limit.reset-per-hour:3}")
    private int resetPerHour;

    private final Map<String, Bucket> aiBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> registerBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> resetBuckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Auth endpoint rate limiting (by IP, POST only)
        if ("POST".equalsIgnoreCase(method)) {
            String ip = request.getRemoteAddr(); // Direct connection only — no X-Forwarded-For trust

            if (path.equals("/api/v1/auth/login")) {
                if (!checkLimit(loginBuckets, ip, loginPerMinute, Duration.ofMinutes(1), response, "login")) return;
            } else if (path.equals("/api/v1/auth/register")) {
                if (!checkLimit(registerBuckets, ip, registerPerMinute, Duration.ofMinutes(1), response, "register")) return;
            } else if (path.equals("/api/v1/auth/reset-password") || path.equals("/api/v1/auth/forgot-password")) {
                if (!checkLimit(resetBuckets, ip, resetPerHour, Duration.ofHours(1), response, "password reset")) return;
            }
        }

        // AI endpoint rate limiting (by authenticated user, POST/PUT only)
        if (path.startsWith("/api/v1/ai/") && !"GET".equalsIgnoreCase(method)) {
            String userId = resolveAuthenticatedUser();
            if (userId != null) {
                Bucket bucket = aiBuckets.computeIfAbsent(userId, k -> Bucket.builder()
                        .addLimit(Bandwidth.classic(aiRequestsPerMinute, Refill.greedy(aiRequestsPerMinute, Duration.ofMinutes(1))))
                        .build());
                if (!bucket.tryConsume(1)) {
                    log.warn("AI rate limit exceeded for user: {} on path: {}", userId, path);
                    writeRateLimitResponse(response, "Too many AI requests. Please wait a moment.", 60);
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean checkLimit(Map<String, Bucket> buckets, String key, int capacity,
                                Duration period, HttpServletResponse response, String endpoint) throws IOException {
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, period)))
                .build());
        if (bucket.tryConsume(1)) return true;

        log.warn("Rate limit exceeded for {} from IP: {}", endpoint, key);
        writeRateLimitResponse(response, "Too many " + endpoint + " attempts. Please try again later.",
                (int) period.toSeconds());
        return false;
    }

    private void writeRateLimitResponse(HttpServletResponse response, String message, int retryAfter) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", message,
                "retryAfterSeconds", retryAfter
        )));
    }

    private String resolveAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return null;
    }
}
