-- Per-document map of raw entities → synthetic replacements generated at
-- ingest time. Stored encrypted at rest (TEXT column because JSON blob is
-- small enough; Postgres jsonb adds no value here and would defeat
-- EncryptionService's AES wrap).
--
-- Access control: only the originating matter's members should be able to
-- read this. Enforced at the service layer, not at the DB — see
-- DocumentService.
ALTER TABLE document_metadata
    ADD COLUMN anonymization_map_json TEXT,
    ADD COLUMN is_anonymized BOOLEAN NOT NULL DEFAULT FALSE;
