ALTER TABLE treasury_users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE treasury_auth_challenges (
    token_hash VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL REFERENCES treasury_users(id) ON DELETE CASCADE,
    purpose VARCHAR(16) NOT NULL,
    expires_at BIGINT NOT NULL,
    UNIQUE (owner_id, purpose)
);
CREATE INDEX treasury_auth_challenges_expiry ON treasury_auth_challenges(expires_at);

CREATE TABLE treasury_mail_outbox (
    token_hash VARCHAR(64) PRIMARY KEY REFERENCES treasury_auth_challenges(token_hash) ON DELETE CASCADE,
    recipient VARCHAR(254) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL
);
