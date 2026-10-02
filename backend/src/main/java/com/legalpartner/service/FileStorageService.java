package com.legalpartner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
@Slf4j
public class FileStorageService {

    private final Path storageDir;

    public FileStorageService(@Value("${legalpartner.storage.path:/data/documents}") String storagePath) {
        this.storageDir = Paths.get(storagePath);
        try { Files.createDirectories(storageDir); }
        catch (IOException e) { log.warn("Could not create storage dir {}: {}", storagePath, e.getMessage()); }
    }

    public String store(UUID documentId, String fileName, byte[] content) throws IOException {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        String storedName = documentId.toString() + ext;
        Path filePath = storageDir.resolve(storedName);
        Files.write(filePath, content);
        log.info("Stored file {} ({} bytes) at {}", fileName, content.length, filePath);
        return filePath.toString();
    }

    public byte[] read(String storedPath) throws IOException {
        return Files.readAllBytes(Paths.get(storedPath));
    }

    public boolean exists(String storedPath) {
        return storedPath != null && Files.exists(Paths.get(storedPath));
    }

    /** Returns the absolute path for a document file (e.g. /data/documents/<id>.docx). */
    /**
     * Delete every stored artifact of a document: {@code <id>.*} files (original, draft
     * HTML/DOCX, manifest, parameters) and the {@code <id>/} version directory.
     * @return number of files removed
     */
    public int deleteAllFor(UUID documentId) {
        int removed = 0;
        String prefix = documentId.toString();
        try (var files = Files.list(storageDir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().startsWith(prefix)).toList()) {
                removed += deleteRecursively(p);
            }
        } catch (IOException e) {
            log.warn("Could not list storage for {}: {}", documentId, e.getMessage());
        }
        return removed;
    }

    private int deleteRecursively(Path p) {
        int n = 0;
        try {
            if (Files.isDirectory(p)) {
                try (var children = Files.list(p)) {
                    for (Path c : children.toList()) n += deleteRecursively(c);
                }
            }
            if (Files.deleteIfExists(p)) n++;
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", p, e.getMessage());
        }
        return n;
    }

    public Path resolve(String fileName) {
        return storageDir.resolve(fileName);
    }
}
