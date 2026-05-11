-- Contract lifecycle columns on document_metadata
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS contract_status VARCHAR(30);
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS is_locked BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS current_version INTEGER;
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS user_brief TEXT;
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS user_key_points TEXT;
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS finalized_at TIMESTAMPTZ;
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS finalized_by VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_doc_contract_status ON document_metadata(contract_status);

-- Document versions
CREATE TABLE IF NOT EXISTS document_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES document_metadata(id) ON DELETE CASCADE,
    version_number  INTEGER NOT NULL,
    stored_path     VARCHAR(500),
    file_size       BIGINT,
    source          VARCHAR(30) NOT NULL DEFAULT 'UPLOAD',
    change_summary  TEXT,
    created_by      VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(document_id, version_number)
);
CREATE INDEX IF NOT EXISTS idx_doc_versions_doc ON document_versions(document_id);

-- Contract deadlines
CREATE TABLE IF NOT EXISTS contract_deadlines (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id         UUID NOT NULL REFERENCES document_metadata(id) ON DELETE CASCADE,
    deadline_type       VARCHAR(50) NOT NULL,
    deadline_date       DATE NOT NULL,
    description         VARCHAR(500),
    is_auto_renewal     BOOLEAN NOT NULL DEFAULT false,
    renewal_term_months INTEGER,
    actioned            BOOLEAN NOT NULL DEFAULT false,
    actioned_by         VARCHAR(100),
    actioned_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_deadlines_doc ON contract_deadlines(document_id);
CREATE INDEX IF NOT EXISTS idx_deadlines_date ON contract_deadlines(deadline_date);
CREATE INDEX IF NOT EXISTS idx_deadlines_actioned ON contract_deadlines(actioned) WHERE actioned = false;

-- Deadline alert config (org-level, seeded with defaults)
CREATE TABLE IF NOT EXISTS deadline_alert_config (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_window_days   INTEGER NOT NULL,
    notify_channel      VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    enabled             BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO deadline_alert_config (alert_window_days, notify_channel)
VALUES (180, 'EMAIL'), (90, 'EMAIL'), (30, 'EMAIL'), (7, 'EMAIL')
ON CONFLICT DO NOTHING;

-- Deadline alerts (generated from config x deadlines)
CREATE TABLE IF NOT EXISTS deadline_alerts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deadline_id         UUID NOT NULL REFERENCES contract_deadlines(id) ON DELETE CASCADE,
    alert_date          DATE NOT NULL,
    alert_window_days   INTEGER NOT NULL,
    sent                BOOLEAN NOT NULL DEFAULT false,
    sent_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_alerts_date_unsent ON deadline_alerts(alert_date) WHERE sent = false;
CREATE INDEX IF NOT EXISTS idx_alerts_deadline ON deadline_alerts(deadline_id);
