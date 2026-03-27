-- Review pipelines (configurable per firm)
CREATE TABLE IF NOT EXISTS review_pipelines (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    is_default  BOOLEAN NOT NULL DEFAULT false,
    created_by  UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Pipeline stages (ordered, configurable actions/roles)
CREATE TABLE IF NOT EXISTS review_stages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id     UUID NOT NULL REFERENCES review_pipelines(id) ON DELETE CASCADE,
    stage_order     INT NOT NULL,
    name            VARCHAR(255) NOT NULL,
    required_role   VARCHAR(50),
    actions         VARCHAR(500) NOT NULL DEFAULT 'APPROVE,RETURN',
    auto_notify     BOOLEAN NOT NULL DEFAULT true
);
CREATE INDEX IF NOT EXISTS idx_review_stages_pipeline ON review_stages(pipeline_id);

-- Track review progress per matter/document
CREATE TABLE IF NOT EXISTS matter_reviews (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    matter_id       UUID NOT NULL REFERENCES matters(id) ON DELETE CASCADE,
    document_id     UUID REFERENCES document_metadata(id) ON DELETE SET NULL,
    pipeline_id     UUID NOT NULL REFERENCES review_pipelines(id),
    current_stage_id UUID REFERENCES review_stages(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    started_by      UUID NOT NULL REFERENCES users(id),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_matter_reviews_matter ON matter_reviews(matter_id);
CREATE INDEX IF NOT EXISTS idx_matter_reviews_status ON matter_reviews(status);

-- Review action log (every action taken)
CREATE TABLE IF NOT EXISTS review_actions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id       UUID NOT NULL REFERENCES matter_reviews(id) ON DELETE CASCADE,
    stage_id        UUID NOT NULL REFERENCES review_stages(id),
    action          VARCHAR(50) NOT NULL,
    acted_by        UUID NOT NULL REFERENCES users(id),
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_review_actions_review ON review_actions(review_id);

-- Add pipeline_id to matters for default pipeline
ALTER TABLE matters ADD COLUMN IF NOT EXISTS review_pipeline_id UUID REFERENCES review_pipelines(id);
