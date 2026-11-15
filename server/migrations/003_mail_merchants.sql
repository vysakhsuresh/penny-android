-- Email forwarding (CP-06). Mail is sealed to the account's public key during the inbound
-- request; only the ciphertext is stored, and only until a device fetches it (14 days at most).

CREATE TABLE IF NOT EXISTS mail_boxes (
    account_id  text PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    local_part  text UNIQUE,
    key_alg     text NOT NULL DEFAULT '' CHECK (key_alg IN ('', 'p256', 'x25519')),
    public_key  bytea NOT NULL DEFAULT '\x'::bytea,
    key_device  text NOT NULL DEFAULT '',
    updated_at  timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS pending_mail (
    id           text PRIMARY KEY,
    account_id   text NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    alg          text NOT NULL,
    blob         bytea NOT NULL,
    received_at  timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS pending_mail_account_idx ON pending_mail(account_id, received_at);
CREATE INDEX IF NOT EXISTS pending_mail_received_idx ON pending_mail(received_at);

-- Community merchant map (PY-03). Deliberately no foreign key to accounts: contributor is an
-- HMAC (key MERCHANT_SALT) of account id, region and descriptor, so a vote cannot be joined
-- to an account or to the same account's other votes. No amounts are stored.
CREATE TABLE IF NOT EXISTS merchant_votes (
    region        text NOT NULL,
    descriptor    text NOT NULL,
    contributor   text NOT NULL,
    name          text NOT NULL,
    name_key      text NOT NULL,
    category_key  text NOT NULL,
    updated_at    timestamptz NOT NULL,
    PRIMARY KEY (region, descriptor, contributor)
);
