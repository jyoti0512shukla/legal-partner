-- Deal Intelligence Agent schema

-- Playbooks: firm's standard positions per deal type
CREATE TABLE IF NOT EXISTS playbooks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    deal_type       VARCHAR(50) NOT NULL,
    description     TEXT,
    is_default      BOOLEAN NOT NULL DEFAULT false,
    created_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Playbook positions: per-clause standards
CREATE TABLE IF NOT EXISTS playbook_positions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    playbook_id         UUID NOT NULL REFERENCES playbooks(id) ON DELETE CASCADE,
    clause_type         VARCHAR(50) NOT NULL,
    standard_position   TEXT NOT NULL,
    minimum_acceptable  TEXT,
    non_negotiable      BOOLEAN NOT NULL DEFAULT false,
    notes               TEXT
);

-- Matter findings: accumulated intelligence per matter
CREATE TABLE IF NOT EXISTS matter_findings (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    matter_id               UUID NOT NULL REFERENCES matters(id) ON DELETE CASCADE,
    document_id             UUID REFERENCES document_metadata(id) ON DELETE SET NULL,
    finding_type            VARCHAR(50) NOT NULL,
    severity                VARCHAR(10) NOT NULL,
    clause_type             VARCHAR(50),
    title                   TEXT NOT NULL,
    description             TEXT NOT NULL,
    section_ref             TEXT,
    playbook_position_id    UUID REFERENCES playbook_positions(id) ON DELETE SET NULL,
    related_document_id     UUID REFERENCES document_metadata(id) ON DELETE SET NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'NEW',
    reviewed_by             UUID REFERENCES users(id),
    reviewed_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Agent config (single-tenant, single row)
CREATE TABLE IF NOT EXISTS agent_config (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auto_analyze_on_upload  BOOLEAN NOT NULL DEFAULT true,
    cross_reference_docs    BOOLEAN NOT NULL DEFAULT true,
    check_playbook          BOOLEAN NOT NULL DEFAULT true,
    notify_high             VARCHAR(50) NOT NULL DEFAULT 'IN_APP',
    notify_medium           VARCHAR(50) NOT NULL DEFAULT 'IN_APP',
    notify_low              VARCHAR(50) NOT NULL DEFAULT 'NONE',
    quiet_hours_start       TIME,
    quiet_hours_end         TIME,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO agent_config (auto_analyze_on_upload, cross_reference_docs, check_playbook)
SELECT true, true, true
WHERE NOT EXISTS (SELECT 1 FROM agent_config);

-- Add deal context to matters
ALTER TABLE matters ADD COLUMN IF NOT EXISTS deal_type VARCHAR(50);
ALTER TABLE matters ADD COLUMN IF NOT EXISTS default_playbook_id UUID REFERENCES playbooks(id);
