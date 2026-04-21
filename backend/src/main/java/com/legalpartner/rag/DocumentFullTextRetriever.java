package com.legalpartner.rag;

import com.legalpartner.service.EncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fetches ALL chunks for a specific document from the pgvector embeddings table,
 * ordered by chunk_index (document reading order).
 *
 * Used for single-document structured tasks (risk assessment, clause checklist,
 * key terms extraction) where you need the full contract text, not just the
 * top-K semantically similar chunks.
 *
 * Context budget for AALAP-Mistral-7B (--max-model-len 8192):
 *   System prompt:    ~500 tokens  (~1,750 chars)
 *   User header:       ~30 tokens  (~  105 chars)
 *   Response budget:  ~300 tokens  (~1,050 chars)
 *   Available for doc: 7,362 tokens ≈ ~9,000 chars @ 3.5 chars/token (Mistral BPE)
 *
 * SHORT_DOC_THRESHOLD: contracts under this size get sent in full.
 * MAX_CHARS: hard cap — contracts above this are truncated.
 * Both can be overridden in application.properties.
 */
@Component
@Slf4j
public class DocumentFullTextRetriever {

    private final JdbcTemplate jdbcTemplate;
    private final EncryptionService encryptionService;

    /**
     * Contracts at or below this character count are sent whole — the model
     * can attend to all clauses without "lost in the middle" degradation.
     * With vLLM max-model-len 16384: ~14K input tokens = ~40K chars safe.
     */
    @Value("${legalpartner.fulltext.short-doc-threshold:40000}")
    private int shortDocThreshold;

    /**
     * Hard cap applied to all documents regardless of length.
     * With vLLM max-model-len 16384 and shortChatModel maxTokens 2000:
     * ~14K tokens for input = ~40K chars. Leaves room for system prompt.
     */
    @Value("${legalpartner.fulltext.max-chars:40000}")
    private int maxChars;

    public DocumentFullTextRetriever(JdbcTemplate jdbcTemplate, EncryptionService encryptionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.encryptionService = encryptionService;
    }

    /**
     * Returns the full plaintext of a document concatenated in reading order.
     * Decrypts each chunk. Truncates at MAX_CHARS with a note if necessary.
     */
    public String retrieveFullText(UUID documentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT text,
                       (metadata->>'chunk_index')::int AS chunk_index,
                       metadata->>'section_path'       AS section_path
                FROM   embeddings
                WHERE  metadata->>'document_id' = ?
                ORDER  BY (metadata->>'chunk_index')::int
                """,
                documentId.toString()
        );

        if (rows.isEmpty()) {
            // Fallback for generated drafts — they have HTML stored but no embeddings.
            // Strip HTML tags and return plain text for summary/risk/QA.
            log.info("No chunks for document {} — trying HTML file fallback", documentId);
            String htmlFallback = readDraftHtmlAsPlainText(documentId);
            if (!htmlFallback.isBlank()) {
                log.info("Full-text retrieval for doc {} via HTML fallback: {} chars", documentId, htmlFallback.length());
                return htmlFallback.length() > maxChars ? htmlFallback.substring(0, maxChars) + "\n\n[...truncated]" : htmlFallback;
            }
            log.warn("No chunks and no HTML file found for document {}", documentId);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        boolean truncated = false;

        for (Map<String, Object> row : rows) {
            String encryptedText = (String) row.get("text");
            String sectionPath   = (String) row.get("section_path");

            String plain;
            try {
                plain = encryptionService.decrypt(encryptedText);
            } catch (Exception e) {
                plain = encryptedText; // fallback to ciphertext if decryption fails
            }

            String block = (sectionPath != null && !sectionPath.isBlank())
                    ? "[" + sectionPath + "]\n" + plain + "\n\n"
                    : plain + "\n\n";

            if (totalChars + block.length() > maxChars) {
                int remaining = maxChars - totalChars;
                if (remaining > 200) {
                    sb.append(block, 0, remaining);
                }
                truncated = true;
                break;
            }
            sb.append(block);
            totalChars += block.length();
        }

        if (truncated) {
            sb.append("\n[... document truncated at ").append(maxChars).append(" chars ...]");
        }

        log.info("Full-text retrieval for doc {}: {} chunks, {} chars{}",
                documentId, rows.size(), totalChars, truncated ? " (truncated)" : "");
        return sb.toString();
    }

    /**
     * Fallback for generated drafts: read the stored HTML file, strip tags,
     * return plain text suitable for summary/risk/QA.
     */
    private String readDraftHtmlAsPlainText(UUID documentId) {
        try {
            // Try to read the stored HTML content from the file storage
            String storagePath = "/data/documents/" + documentId + ".html";
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(storagePath));
            String html = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            // Strip HTML tags, decode entities, normalize whitespace
            String plain = html
                    .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                    .replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&#x23F3;", "")
                    .replaceAll("\\s{2,}", " ")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
            return plain;
        } catch (Exception e) {
            log.debug("HTML fallback read failed for {}: {}", documentId, e.getMessage());
            return "";
        }
    }

    /** Returns the number of indexed chunks for a document. */
    public int countChunks(UUID documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE metadata->>'document_id' = ?",
                Integer.class,
                documentId.toString()
        );
        return count != null ? count : 0;
    }
}
