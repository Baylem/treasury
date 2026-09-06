CREATE TABLE treasury_oauth_devices (
    id VARCHAR(36) PRIMARY KEY,
    secret_hash VARCHAR(64) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    provider VARCHAR(16) NOT NULL,
    expires_at BIGINT NOT NULL,
    owner_id VARCHAR(36) REFERENCES treasury_users(id) ON DELETE CASCADE,
    approval_hash VARCHAR(64),
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX treasury_oauth_devices_expiry ON treasury_oauth_devices(expires_at);
ALTER TABLE treasury_oauth_flows ADD COLUMN device_attempt_id VARCHAR(36) REFERENCES treasury_oauth_devices(id) ON DELETE CASCADE;
