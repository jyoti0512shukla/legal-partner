-- Trusted devices — skip MFA on known browsers
CREATE TABLE IF NOT EXISTS trusted_devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_token    VARCHAR(255) NOT NULL UNIQUE,
    user_agent      TEXT,
    ip_address      VARCHAR(50),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    last_used_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_trusted_devices_user ON trusted_devices(user_id);
CREATE INDEX IF NOT EXISTS idx_trusted_devices_token ON trusted_devices(device_token);

-- Backup codes — 10 single-use recovery codes per user
CREATE TABLE IF NOT EXISTS mfa_backup_codes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash       VARCHAR(255) NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT false,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_backup_codes_user ON mfa_backup_codes(user_id);

-- Org-level MFA settings
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_method VARCHAR(20) DEFAULT 'NONE';
-- NONE, TOTP, EMAIL_OTP

-- Add org MFA enforcement to auth_config (existing table)
-- auth_config already has: require_mfa, mfa_grace_period_days
-- Let's verify and add if missing
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'auth_config' AND column_name = 'require_mfa') THEN
        ALTER TABLE auth_config ADD COLUMN require_mfa BOOLEAN NOT NULL DEFAULT false;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'auth_config' AND column_name = 'mfa_grace_period_days') THEN
        ALTER TABLE auth_config ADD COLUMN mfa_grace_period_days INTEGER NOT NULL DEFAULT 7;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'auth_config' AND column_name = 'mfa_trust_days') THEN
        ALTER TABLE auth_config ADD COLUMN mfa_trust_days INTEGER NOT NULL DEFAULT 90;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'auth_config' AND column_name = 'allowed_mfa_methods') THEN
        ALTER TABLE auth_config ADD COLUMN allowed_mfa_methods VARCHAR(50) NOT NULL DEFAULT 'TOTP,EMAIL_OTP';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'auth_config' AND column_name = 'session_idle_minutes') THEN
        ALTER TABLE auth_config ADD COLUMN session_idle_minutes INTEGER NOT NULL DEFAULT 30;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'auth_config' AND column_name = 'session_absolute_hours') THEN
        ALTER TABLE auth_config ADD COLUMN session_absolute_hours INTEGER NOT NULL DEFAULT 12;
    END IF;
END $$;
