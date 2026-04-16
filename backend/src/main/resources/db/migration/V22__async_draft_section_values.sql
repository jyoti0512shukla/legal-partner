-- Per-clause HTML storage for async drafts, so a failed draft can resume
-- from the first incomplete clause instead of starting over from scratch.
-- JSON keyed by clause type: {"DEFINITIONS": "<html>...", "SERVICES": "<html>..."}
ALTER TABLE document_metadata
    ADD COLUMN section_values_json TEXT;
