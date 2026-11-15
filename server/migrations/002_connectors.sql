-- Bank connectors (CP-10, SE-08). Provider credentials are stored only as AES-256-GCM
-- ciphertext (key from CONNECTOR_KEY, never in the database). No transaction data is stored:
-- the cursor holds the last fetch time and truncated hashes of seen transaction ids (60 days).

CREATE TABLE IF NOT EXISTS bank_connections (
    id              text PRIMARY KEY,
    account_id      text NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    provider        text NOT NULL,
    institution     text NOT NULL,
    status          text NOT NULL CHECK (status IN ('active', 'needs_consent', 'broken', 'disconnected')),
    consent_expiry  timestamptz,
    secret          bytea,
    cursor          bytea NOT NULL DEFAULT '\x'::bytea,
    last_synced_at  timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS bank_connections_account_idx ON bank_connections(account_id);

-- Pending redirect consents; only SHA-256 of the state value is stored. Short-lived.
CREATE TABLE IF NOT EXISTS connect_states (
    state_hash     text PRIMARY KEY,
    account_id     text NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    provider       text NOT NULL,
    connection_id  text REFERENCES bank_connections(id) ON DELETE CASCADE,
    redirect_uri   text NOT NULL,
    expires_at     timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS connect_states_account_idx ON connect_states(account_id);

-- Plus entitlement; only SHA-256 of the store purchase token is stored.
CREATE TABLE IF NOT EXISTS entitlements (
    account_id  text PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    store       text NOT NULL,
    product_id  text NOT NULL,
    token_hash  text NOT NULL,
    plus_until  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);
