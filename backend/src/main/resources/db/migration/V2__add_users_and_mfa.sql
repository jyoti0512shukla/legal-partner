-- Users table
CREATE TABLE users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                 VARCHAR(255) NOT NULL UNIQUE,
    password_hash         VARCHAR(255) NOT NULL,
    display_name          VARCHAR(100),
    role                  VARCHAR(50) NOT NULL DEFAULT 'ASSOCIATE',
    mfa_enabled           BOOLEAN NOT NULL DEFAULT false,
    enabled               BOOLEAN NOT NULL DEFAULT true,
    password_changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    must_change_password  BOOLEAN NOT NULL DEFAULT false,
    failed_login_count    INTEGER NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Password history (for reuse prevention)
CREATE TABLE password_history (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    password_hash     VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_history_user ON password_history(user_id);

-- MFA secrets (TOTP) - stored separately for clarity
CREATE TABLE user_mfa_secrets (
    user_id           UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret            VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);
