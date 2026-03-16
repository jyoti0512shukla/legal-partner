-- Cloud storage OAuth connections (per user, per provider)
CREATE TABLE cloud_storage_connection (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider          VARCHAR(50) NOT NULL,
    access_token      VARCHAR(2000) NOT NULL,
    refresh_token     VARCHAR(2000),
    token_expires_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, provider)
);

CREATE INDEX idx_cloud_storage_user ON cloud_storage_connection(user_id);
CREATE INDEX idx_cloud_storage_provider ON cloud_storage_connection(provider);
