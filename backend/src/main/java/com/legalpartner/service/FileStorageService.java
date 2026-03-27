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
}
