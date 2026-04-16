-- Cached AI summary for each document. Populated on demand by /api/v1/ai/summarize/{id}.
ALTER TABLE document_metadata
    ADD COLUMN summary_text TEXT,
    ADD COLUMN summary_generated_at TIMESTAMP;
