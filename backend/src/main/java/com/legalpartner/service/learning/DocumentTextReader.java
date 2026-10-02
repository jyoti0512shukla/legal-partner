package com.legalpartner.service.learning;

import com.legalpartner.rag.HtmlText;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Plain text of a stored document version (HTML drafts or DOCX/PDF saved from the editor). */
@Component
@Slf4j
public class DocumentTextReader {

    private final Tika tika = new Tika();

    public String read(String storedPath) {
        if (storedPath == null) return "";
        try {
            Path p = Path.of(storedPath);
            if (!Files.exists(p)) return "";
            byte[] bytes = Files.readAllBytes(p);
            String head = new String(bytes, 0, Math.min(bytes.length, 512), java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
            if (head.contains("<html") || head.contains("<!doctype") || head.contains("<h2")) {
                return HtmlText.toPlainText(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
            try (var in = new java.io.ByteArrayInputStream(bytes)) {
                return tika.parseToString(in);
            }
        } catch (Exception e) {
            log.warn("Could not read text of {}: {}", storedPath, e.getMessage());
            return "";
        }
    }
}
