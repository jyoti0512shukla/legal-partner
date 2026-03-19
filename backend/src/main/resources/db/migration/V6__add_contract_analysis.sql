-- Stores the most recent risk assessment and clause checklist results per document.
-- One row per (document_id, analysis_type) — upserted on each run.
CREATE TABLE contract_analysis (
    id            UUID                        PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   UUID                        NOT NULL REFERENCES document_metadata(id) ON DELETE CASCADE,
    analysis_type VARCHAR(20)                 NOT NULL,   -- 'RISK' or 'CHECKLIST'
    result_json   TEXT                        NOT NULL,
    analyzed_at   TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    analyzed_by   VARCHAR(100)                NOT NULL,
    CONSTRAINT contract_analysis_doc_type_uq UNIQUE (document_id, analysis_type)
);
CREATE INDEX idx_contract_analysis_doc ON contract_analysis(document_id);
