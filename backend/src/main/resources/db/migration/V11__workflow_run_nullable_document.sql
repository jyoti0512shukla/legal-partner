-- Allow workflow runs without a document (e.g. draft-only workflows)
ALTER TABLE workflow_runs ALTER COLUMN document_id DROP NOT NULL;
