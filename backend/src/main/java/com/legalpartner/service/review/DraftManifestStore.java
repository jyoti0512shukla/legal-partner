package com.legalpartner.service.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Reads and writes {@link DraftManifest} sidecar files ({@code <docId>.draft.json}). */
@Component
@Slf4j
public class DraftManifestStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path storageDir;

    public DraftManifestStore(@Value("${legalpartner.storage.path:/data/documents}") String storagePath) {
        this.storageDir = Path.of(storagePath);
    }

    Path pathFor(UUID docId) {
        return storageDir.resolve(docId + ".draft.json");
    }

    public void write(UUID docId, DraftManifest manifest) {
        try {
            Files.createDirectories(storageDir);
            Files.writeString(pathFor(docId), MAPPER.writeValueAsString(manifest), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Non-fatal: review falls back to the text path.
            log.warn("Could not write draft manifest for {}: {}", docId, e.getMessage());
        }
    }

    public Optional<DraftManifest> read(UUID docId) {
        Path p = pathFor(docId);
        if (!Files.exists(p)) return Optional.empty();
        try {
            DraftManifest m = MAPPER.readValue(Files.readString(p, StandardCharsets.UTF_8), DraftManifest.class);
            if (m.sections() == null || m.sections().isEmpty()) return Optional.empty();
            return Optional.of(m);
        } catch (Exception e) {
            log.warn("Unreadable draft manifest for {} ({}); falling back to text review", docId, e.getMessage());
            return Optional.empty();
        }
    }
}
