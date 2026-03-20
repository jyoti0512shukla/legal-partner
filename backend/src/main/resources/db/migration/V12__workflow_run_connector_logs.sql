-- Track per-connector fire results on workflow runs
ALTER TABLE workflow_runs ADD COLUMN IF NOT EXISTS connector_logs JSONB NOT NULL DEFAULT '[]';
