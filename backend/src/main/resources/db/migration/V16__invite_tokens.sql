-- Invite and password reset tokens
CREATE TABLE IF NOT EXISTS auth_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       VARCHAR(255) NOT NULL UNIQUE,
    token_type  VARCHAR(20) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_auth_tokens_token ON auth_tokens(token);
CREATE INDEX IF NOT EXISTS idx_auth_tokens_user ON auth_tokens(user_id);

-- Auth/user management config (single row)
CREATE TABLE IF NOT EXISTS auth_config (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invite_expiry_hours         INT NOT NULL DEFAULT 72,
    invite_resend_cooldown_min  INT NOT NULL DEFAULT 30,
    password_reset_expiry_hours INT NOT NULL DEFAULT 24,
    max_password_resets_per_hour INT NOT NULL DEFAULT 3,
    max_failed_logins           INT NOT NULL DEFAULT 5,
    lockout_duration_minutes    INT NOT NULL DEFAULT 15,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO auth_config (invite_expiry_hours, invite_resend_cooldown_min, password_reset_expiry_hours, max_password_resets_per_hour)
VALUES (72, 30, 24, 3);

-- Add account_status to users for invite tracking
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
