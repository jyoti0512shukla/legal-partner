-- Cached AI risk-assessment and extraction/checklist results on document_metadata.
-- Follows the same pattern as V21 (summary_text / summary_generated_at).

ALTER TABLE document_metadata
    ADD COLUMN IF NOT EXISTS risk_assessment_json TEXT,
    ADD COLUMN IF NOT EXISTS risk_assessment_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS extraction_json      TEXT,
    ADD COLUMN IF NOT EXISTS extraction_at        TIMESTAMP;
