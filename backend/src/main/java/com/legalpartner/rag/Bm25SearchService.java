package com.legalpartner.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * BM25-style sparse retrieval via Postgres full-text search (tsvector + ts_rank).
 * Complements dense vector retrieval — legal text has specific terminology
 * (defined terms, section numbers, case citations) that lexical matching
 * catches better than embeddings alone.
 *
 * Fused with dense results via Reciprocal Rank Fusion in DraftContextRetriever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Bm25SearchService {

    private final JdbcTemplate jdbcTemplate;

    /** Result row from the BM25 query. */
    public record Bm25Hit(UUID chunkId, UUID documentId, double rank) {}

    /**
     * Run a BM25-style FTS query, return the top-N chunk ids by ts_rank.
     * Uses `plainto_tsquery` which tokenizes the input naively (doesn't
     * interpret operators) — safe for raw user queries without escaping.
     */
    public List<Bm25Hit> search(String queryText, int topN) {
        if (queryText == null || queryText.isBlank() || topN <= 0) return List.of();
        try {
            return jdbcTemplate.query(
                    "SELECT chunk_id, document_id, ts_rank(tsv, q) AS rank " +
                    "FROM chunk_search_index, plainto_tsquery('english', ?) AS q " +
                    "WHERE tsv @@ q " +
                    "ORDER BY rank DESC " +
                    "LIMIT ?",
                    (rs, rowNum) -> new Bm25Hit(
                            UUID.fromString(rs.getString("chunk_id")),
                            UUID.fromString(rs.getString("document_id")),
                            rs.getDouble("rank")),
                    queryText, topN);
        } catch (Exception e) {
            log.warn("BM25 search failed for query '{}': {}",
                    queryText.length() > 80 ? queryText.substring(0, 80) + "..." : queryText,
                    e.getMessage());
            return List.of();
        }
    }

    /** Insert/update a chunk's plain text into the FTS index. Idempotent. */
    public void upsertChunk(UUID chunkId, UUID documentId, String plainText) {
        if (chunkId == null || documentId == null || plainText == null || plainText.isBlank()) return;
        try {
            jdbcTemplate.update(
                    "INSERT INTO chunk_search_index (chunk_id, document_id, plain_text) " +
                    "VALUES (?, ?, ?) " +
                    "ON CONFLICT (chunk_id) DO UPDATE SET " +
                    "  document_id = EXCLUDED.document_id, " +
                    "  plain_text  = EXCLUDED.plain_text",
                    chunkId, documentId, plainText);
        } catch (Exception e) {
            log.warn("BM25 upsert failed for chunk {}: {}", chunkId, e.getMessage());
        }
    }

    /** Remove all FTS entries for a document (used before re-indexing). */
    public int deleteByDocument(UUID documentId) {
        try {
            return jdbcTemplate.update(
                    "DELETE FROM chunk_search_index WHERE document_id = ?", documentId);
        } catch (Exception e) {
            log.warn("BM25 delete failed for doc {}: {}", documentId, e.getMessage());
            return 0;
        }
    }
}
