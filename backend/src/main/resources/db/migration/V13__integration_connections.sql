CREATE TABLE integration_connection (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider          VARCHAR(50) NOT NULL,
    access_token      VARCHAR(4000),
    refresh_token     VARCHAR(4000),
    token_expires_at  TIMESTAMPTZ,
    config            JSONB NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, provider)
);
CREATE INDEX idx_integration_conn_user ON integration_connection(user_id);
