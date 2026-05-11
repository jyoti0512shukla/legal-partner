-- Document notes
CREATE TABLE IF NOT EXISTS document_notes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES document_metadata(id) ON DELETE CASCADE,
    content         TEXT NOT NULL,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_document_notes_doc ON document_notes(document_id);
