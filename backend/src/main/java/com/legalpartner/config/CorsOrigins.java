package com.legalpartner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Browser origins allowed to call the API with credentials.
 *
 * {@code legalpartner.cors.allowed-origins} (env LEGALPARTNER_CORS_ALLOWED_ORIGINS) is a
 * comma-separated list of exact origins — e.g. a firm's domain plus a temporary address while
 * DNS is being set up. When empty, the single frontend URL is used. Wildcards are rejected:
 * with credentials allowed, every origin must be named explicitly.
 */
@Component
public class CorsOrigins {

    private final List<String> origins;

    public CorsOrigins(@Value("${legalpartner.cors.allowed-origins:}") String allowedOrigins,
                       @Value("${legalpartner.cloud.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.origins = parse(allowedOrigins == null || allowedOrigins.isBlank() ? frontendUrl : allowedOrigins);
    }

    public List<String> origins() {
        return origins;
    }

    static List<String> parse(String csv) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : Arrays.asList(csv.split(","))) {
            String o = raw.trim();
            while (o.endsWith("/")) o = o.substring(0, o.length() - 1);
            if (o.isEmpty()) continue;
            if (o.contains("*")) {
                throw new IllegalArgumentException("CORS origins must not contain wildcards: " + o);
            }
            if (!o.startsWith("https://") && !o.startsWith("http://")) {
                throw new IllegalArgumentException("CORS origin must start with http:// or https://: " + o);
            }
            out.add(o);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("No CORS origins configured");
        return List.copyOf(new ArrayList<>(out));
    }
}
