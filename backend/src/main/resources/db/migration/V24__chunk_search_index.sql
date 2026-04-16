-- Postgres full-text index over anonymized chunk text, for BM25-style
-- sparse retrieval alongside our existing dense vector search.
--
-- Legal text is lexically dense (defined terms, section numbers, specific
-- statutes) — BM25 often beats pure dense retrieval on these. Fusing both
-- via RRF (Reciprocal Rank Fusion) gives +17-39% recall@5 in published
-- benchmarks (Superlinked VectorHub, 2025).
--
-- Populated at ingest time from the ANONYMIZED chunk text, so it's safe
-- to surface in drafts without cross-client leakage.
CREATE TABLE IF NOT EXISTS chunk_search_index (
    chunk_id    UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    plain_text  TEXT NOT NULL,
    tsv         TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', plain_text)) STORED
);

CREATE INDEX IF NOT EXISTS idx_chunk_search_tsv ON chunk_search_index USING GIN (tsv);
CREATE INDEX IF NOT EXISTS idx_chunk_search_doc ON chunk_search_index (document_id);
