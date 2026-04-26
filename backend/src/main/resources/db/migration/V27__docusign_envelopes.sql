-- Organization-scoped connections: admin connects once, all users can send
ALTER TABLE integration_connection
    ADD COLUMN IF NOT EXISTS scope VARCHAR(20) NOT NULL DEFAULT 'USER';
-- Backfill: existing DOCUSIGN connections become org-level
UPDATE integration_connection SET scope = 'ORGANIZATION' WHERE provider = 'DOCUSIGN';

-- Track DocuSign envelopes sent from the system
CREATE TABLE IF NOT EXISTS signature_envelopes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    envelope_id     VARCHAR(100) NOT NULL UNIQUE,
    document_id     UUID REFERENCES document_metadata(id),
    matter_id       UUID REFERENCES matters(id),
    sent_by         UUID REFERENCES users(id),
    status          VARCHAR(30) NOT NULL DEFAULT 'sent',
    recipients      JSONB NOT NULL DEFAULT '[]',
    email_subject   TEXT,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    voided_at       TIMESTAMPTZ,
    signed_pdf_path TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sig_envelopes_doc ON signature_envelopes(document_id);
CREATE INDEX IF NOT EXISTS idx_sig_envelopes_matter ON signature_envelopes(matter_id);
CREATE INDEX IF NOT EXISTS idx_sig_envelopes_status ON signature_envelopes(status);
