package com.legalpartner.rag;

import com.legalpartner.service.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * top-K semantically similar chunks. Sending the whole document avoids the
 * "lost in the middle" problem and ensures missing clauses (e.g. absent force
 * majeure) are correctly detected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentFullTextRetriever {

    private final JdbcTemplate jdbcTemplate;
    private final EncryptionService encryptionService;

    // Max chars to send to the model — stay within the model's context window.
    // AALAP/Mistral 7B: 8192 tokens ≈ ~24k chars. Use 18k to leave room for prompt.
    private static final int MAX_CHARS = 18_000;

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
            log.warn("No chunks found for document {}", documentId);
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

            if (totalChars + block.length() > MAX_CHARS) {
                int remaining = MAX_CHARS - totalChars;
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
            sb.append("\n[... document truncated at ").append(MAX_CHARS).append(" chars ...]");
        }

        log.info("Full-text retrieval for doc {}: {} chunks, {} chars{}",
                documentId, rows.size(), totalChars, truncated ? " (truncated)" : "");
        return sb.toString();
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
