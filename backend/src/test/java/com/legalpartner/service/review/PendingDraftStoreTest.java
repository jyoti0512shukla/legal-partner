package com.legalpartner.service.review;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PendingDraftStoreTest {

    private static PendingDraftStore.PendingDraft draft(String user, Instant created) {
        var m = new DraftManifest(1, "nda", "NDA", null, null, null, List.of());
        return new PendingDraftStore.PendingDraft(user, "nda", "NDA", m, List.of(), "text", created);
    }

    @Test
    void tokenIsSingleUseAndOwnedByItsUser() {
        var store = new PendingDraftStore(24, 10);
        String token = store.put(draft("alice", Instant.now()));
        assertThat(store.take(token, "mallory")).isEmpty();
        assertThat(store.take(token, "alice")).isPresent();
        assertThat(store.take(token, "alice")).isEmpty();
        assertThat(store.take(null, "alice")).isEmpty();
    }

    @Test
    void expiredAndOverflowingEntriesAreDropped() {
        var store = new PendingDraftStore(1, 2);
        String old = store.put(draft("a", Instant.now().minus(Duration.ofHours(2))));
        assertThat(store.take(old, "a")).isEmpty();
        String first = store.put(draft("a", Instant.now().minusSeconds(10)));
        store.put(draft("a", Instant.now()));
        store.put(draft("a", Instant.now()));
        assertThat(store.take(first, "a")).isEmpty(); // oldest evicted at capacity
    }
}
