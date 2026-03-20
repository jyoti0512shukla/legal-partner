-- Distinguish user-uploaded documents from corpus seed documents (EDGAR imports, etc.)
-- Documents tab shows only USER and CLOUD sources; EDGAR seeds are RAG-only background data.
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'USER';
-- Retrospectively mark existing EDGAR-imported docs (filename prefix set by EdgarImportService)
UPDATE document_metadata SET source = 'EDGAR' WHERE file_name LIKE 'EDGAR\_%' ESCAPE '\';

CREATE INDEX IF NOT EXISTS idx_document_metadata_source ON document_metadata(source);
