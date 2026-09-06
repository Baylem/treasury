CREATE TABLE treasury_users (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(254) UNIQUE,
    password_hash VARCHAR(512),
    created_at BIGINT NOT NULL,
    last_change BIGINT NOT NULL DEFAULT 0,
    CHECK (password_hash IS NULL OR email IS NOT NULL)
);

CREATE TABLE treasury_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL REFERENCES treasury_users(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX treasury_sessions_owner ON treasury_sessions(owner_id);
CREATE INDEX treasury_sessions_expiry ON treasury_sessions(expires_at);

CREATE TABLE treasury_oauth_identities (
    provider VARCHAR(16) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    owner_id VARCHAR(36) NOT NULL REFERENCES treasury_users(id) ON DELETE CASCADE,
    PRIMARY KEY (provider, subject)
);

CREATE TABLE treasury_oauth_flows (
    state_hash VARCHAR(64) PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    verifier VARCHAR(128) NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX treasury_oauth_flows_expiry ON treasury_oauth_flows(expires_at);

CREATE TABLE treasury_records (
    owner_id VARCHAR(36) NOT NULL REFERENCES treasury_users(id) ON DELETE CASCADE,
    id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('account', 'entry', 'plan', 'override')),
    payload TEXT NOT NULL,
    server_updated_at BIGINT NOT NULL,
    PRIMARY KEY (owner_id, id)
);
CREATE UNIQUE INDEX treasury_records_delta ON treasury_records(owner_id, server_updated_at);
