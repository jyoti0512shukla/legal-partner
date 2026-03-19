ALTER TABLE workflow_definitions
    ADD COLUMN IF NOT EXISTS auto_trigger BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS connectors   JSONB NOT NULL DEFAULT '[]';

CREATE INDEX IF NOT EXISTS idx_workflow_definitions_auto_trigger
    ON workflow_definitions(auto_trigger) WHERE auto_trigger = true;
