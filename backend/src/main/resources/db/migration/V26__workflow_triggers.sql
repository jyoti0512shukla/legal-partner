-- Add triggers JSONB column to workflow_definitions
ALTER TABLE workflow_definitions
    ADD COLUMN IF NOT EXISTS triggers JSONB NOT NULL DEFAULT '[]';

-- Migrate existing autoTrigger=true to triggers array
UPDATE workflow_definitions
SET triggers = '[{"event": "DOCUMENT_INDEXED"}]'::jsonb
WHERE auto_trigger = true AND triggers = '[]'::jsonb;
