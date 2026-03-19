ALTER TABLE workflow_runs
    ADD COLUMN IF NOT EXISTS matter_ref    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS skipped_steps JSONB NOT NULL DEFAULT '[]';

ALTER TABLE workflow_definitions
    ADD COLUMN IF NOT EXISTS is_team BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_workflow_runs_matter ON workflow_runs(matter_ref);
CREATE INDEX IF NOT EXISTS idx_workflow_definitions_team ON workflow_definitions(is_team);
