ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS stored_path VARCHAR(500);
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS file_size BIGINT;
