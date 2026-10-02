package com.legalpartner.service.review;

import com.legalpartner.service.learning.ExposureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the structured record of a draft generated without a document row (sync / streaming
 * drafting) until the user saves it. The draft response carries an opaque token; saving with
 * that token attaches the draft manifest (review handoff) and exposure log (learning) to the
 * new document — the same records an async draft gets at generation time.
 *
 * In memory, per user, expiring after {@code legalpartner.draft.pending.ttl-hours}; a draft
 * saved after expiry (or after a restart) is simply reviewed and learned from as plain text.
 */
@Component
@Slf4j
public class PendingDraftStore {

    /** What a saved draft needs from its generation run. */
    public record PendingDraft(String username, String templateId, String contractType, DraftManifest manifest,
                               List<ExposureService.Exposure> exposures, String generatedPlainText, Instant createdAt) {}

    private final Map<String, PendingDraft> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;
    private final int maxEntries;

    public PendingDraftStore(@Value("${legalpartner.draft.pending.ttl-hours:24}") long ttlHours,
                             @Value("${legalpartner.draft.pending.max-entries:500}") int maxEntries) {
        this.ttl = Duration.ofHours(ttlHours);
        this.maxEntries = maxEntries;
    }

    public String put(PendingDraft draft) {
        evictExpired();
        if (pending.size() >= maxEntries) {
            pending.entrySet().stream().min(Map.Entry.comparingByValue(
                            java.util.Comparator.comparing(PendingDraft::createdAt)))
                    .ifPresent(oldest -> pending.remove(oldest.getKey()));
        }
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        pending.put(token, draft);
        return token;
    }

    /** Removes and returns the pending draft if the token exists, is fresh and belongs to {@code username}. */
    public Optional<PendingDraft> take(String token, String username) {
        if (token == null || token.isBlank()) return Optional.empty();
        PendingDraft d = pending.get(token);
        if (d == null) return Optional.empty();
        if (!d.username().equals(username)) {
            log.warn("Pending draft token used by a different user — ignored");
            return Optional.empty();
        }
        pending.remove(token);
        return expired(d) ? Optional.empty() : Optional.of(d);
    }

    private void evictExpired() {
        pending.values().removeIf(this::expired);
    }

    private boolean expired(PendingDraft d) {
        return d.createdAt().plus(ttl).isBefore(Instant.now());
    }
}
