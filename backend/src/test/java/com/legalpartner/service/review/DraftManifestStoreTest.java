package com.legalpartner.service.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DraftManifestStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTrips() {
        var store = new DraftManifestStore(dir.toString());
        UUID id = UUID.randomUUID();
        var manifest = new DraftManifest(DraftManifest.CURRENT_VERSION, "nda", "NDA", "PARTY_A", "BALANCED", "Delaware",
                List.of(new DraftManifest.Section("CONFIDENTIALITY", "Confidentiality", 2, List.of("CONFIDENTIALITY"), "text")));
        store.write(id, manifest);
        assertThat(store.read(id)).contains(manifest);
    }

    @Test
    void missingOrCorruptManifestFallsBackToEmpty() throws Exception {
        var store = new DraftManifestStore(dir.toString());
        UUID id = UUID.randomUUID();
        assertThat(store.read(id)).isEmpty();
        Files.writeString(store.pathFor(id), "{not json");
        assertThat(store.read(id)).isEmpty();
    }

    @Test
    void ignoresUnknownFieldsForForwardCompatibility() throws Exception {
        var store = new DraftManifestStore(dir.toString());
        UUID id = UUID.randomUUID();
        Files.writeString(store.pathFor(id), """
                {"version":2,"templateId":"nda","reviewType":"NDA","futureField":1,
                 "sections":[{"key":"CONFIDENTIALITY","title":"C","article":1,"text":"t","extra":true}]}
                """);
        assertThat(store.read(id)).get().extracting(DraftManifest::reviewType).isEqualTo("NDA");
    }
}
