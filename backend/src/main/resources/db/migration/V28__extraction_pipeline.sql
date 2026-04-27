-- Extraction pipeline cache on document_metadata
ALTER TABLE document_metadata
    ADD COLUMN IF NOT EXISTS key_terms_json        TEXT,
    ADD COLUMN IF NOT EXISTS key_terms_at           TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS key_terms_corrections  TEXT;

-- Self-improving canonical alias overrides (from user corrections)
CREATE TABLE IF NOT EXISTS alias_overrides (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_field       VARCHAR(200) NOT NULL,
    canonical_field VARCHAR(100) NOT NULL,
    created_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(raw_field)
);

CREATE INDEX IF NOT EXISTS idx_alias_overrides_raw ON alias_overrides(raw_field);
