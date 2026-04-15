-- Async draft generation progress tracking.
-- Drafts run on a background thread; the frontend polls these columns.
ALTER TABLE document_metadata
    ADD COLUMN total_clauses INT,
    ADD COLUMN completed_clauses INT,
    ADD COLUMN current_clause_label VARCHAR(255),
    ADD COLUMN last_progress_at TIMESTAMP,
    ADD COLUMN error_message VARCHAR(1000);

-- Drafts are queried by (uploadedBy, source) and polled frequently by UUID.
-- Primary key already covers by-id lookups. Add an index for the list endpoint.
CREATE INDEX IF NOT EXISTS idx_document_metadata_drafts
    ON document_metadata (uploaded_by, source, upload_date DESC)
    WHERE source = 'DRAFT_ASYNC';
