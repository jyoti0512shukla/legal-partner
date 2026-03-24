CREATE TABLE matter_members (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    matter_id   UUID NOT NULL REFERENCES matters(id) ON DELETE CASCADE,
    user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    email       VARCHAR(255) NOT NULL,
    matter_role VARCHAR(50) NOT NULL,
    added_by    UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_matter_member_user UNIQUE (matter_id, user_id),
    CONSTRAINT uq_matter_member_external UNIQUE (matter_id, email)
);

CREATE INDEX idx_matter_members_matter ON matter_members(matter_id);
CREATE INDEX idx_matter_members_user   ON matter_members(user_id);
CREATE INDEX idx_matter_members_email  ON matter_members(email);

ALTER TABLE workflow_runs ADD COLUMN matter_id UUID REFERENCES matters(id);
CREATE INDEX idx_workflow_runs_matter ON workflow_runs(matter_id);
