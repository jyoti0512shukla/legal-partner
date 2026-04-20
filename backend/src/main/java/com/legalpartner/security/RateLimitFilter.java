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
 * Per-user rate limiting for AI endpoints.
 * Law firm users run heavy Mistral queries — cap at reasonable limit to prevent
 * one user starving the shared Ollama instance.
 */
@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${legalpartner.rate-limit.ai-requests-per-minute:15}")
    private int aiRequestsPerMinute;

    private final Map<String, Bucket> userBuckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        // Only rate-limit POST/PUT on AI endpoints — these hit the LLM.
        // GET requests (draft status polling, draft list, cached summaries)
        // are cheap DB reads and must NOT consume rate-limit tokens.
        if (!path.startsWith("/api/v1/ai/") || "GET".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = resolveUserId(request);
        Bucket bucket = userBuckets.computeIfAbsent(userId, this::createBucket);

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for user: {} on path: {}", userId, path);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                    "error", "Too many AI requests. Please wait a moment before trying again.",
                    "retryAfterSeconds", 60
            )));
        }
    }

    private Bucket createBucket(String userId) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(
                        aiRequestsPerMinute,
                        Refill.greedy(aiRequestsPerMinute, Duration.ofMinutes(1))
                ))
                .build();
    }

    private String resolveUserId(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        // Fall back to IP address for unauthenticated requests
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
