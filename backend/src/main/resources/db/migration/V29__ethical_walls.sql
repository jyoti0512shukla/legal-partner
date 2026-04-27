-- Ethical walls between matters — prevents data leakage between conflicting clients
CREATE TABLE IF NOT EXISTS ethical_walls (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    matter_a_id     UUID NOT NULL REFERENCES matters(id),
    matter_b_id     UUID NOT NULL REFERENCES matters(id),
    reason          TEXT NOT NULL,
    created_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    active          BOOLEAN NOT NULL DEFAULT true,
    UNIQUE(matter_a_id, matter_b_id)
);

CREATE INDEX IF NOT EXISTS idx_ethical_walls_a ON ethical_walls(matter_a_id) WHERE active = true;
CREATE INDEX IF NOT EXISTS idx_ethical_walls_b ON ethical_walls(matter_b_id) WHERE active = true;
