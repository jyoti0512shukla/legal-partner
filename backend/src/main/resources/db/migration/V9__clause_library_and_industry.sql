-- V9: Add industry to document_metadata + create firm clause library table

ALTER TABLE document_metadata
    ADD COLUMN IF NOT EXISTS industry VARCHAR(50);

CREATE TABLE IF NOT EXISTS clause_library (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    clause_type   VARCHAR(50) NOT NULL,
    title         VARCHAR(200) NOT NULL,
    content       TEXT        NOT NULL,
    contract_type VARCHAR(50),          -- NDA, MSA, SOW, null = any
    practice_area VARCHAR(50),          -- CORPORATE, IP, etc., null = any
    industry      VARCHAR(50),          -- FINTECH, PHARMA, IT_SERVICES, null = any
    counterparty_type VARCHAR(50),      -- vendor, client, employee, null = any
    jurisdiction  VARCHAR(100),         -- null = any
    is_golden     BOOLEAN     NOT NULL DEFAULT FALSE,
    usage_count   INTEGER     NOT NULL DEFAULT 0,
    created_by    VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_clause_library_type   ON clause_library(clause_type);
CREATE INDEX IF NOT EXISTS idx_clause_library_golden ON clause_library(is_golden) WHERE is_golden = TRUE;
