-- Penny relay schema. The server stores ciphertext and routing metadata only.
-- No emails are stored: accounts are found by a keyed hash (HMAC-SHA256) of the address.

CREATE TABLE IF NOT EXISTS accounts (
    id          text PRIMARY KEY,
    email_hash  text NOT NULL UNIQUE,
    salt        text NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_codes (
    email_hash  text PRIMARY KEY,
    code_hash   text NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    integer NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS devices (
    id          text PRIMARY KEY,
    account_id  text NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    name        text NOT NULL,
    platform    text NOT NULL,
    public_key  text NOT NULL,
    created_at  timestamptz NOT NULL,
    last_seen   timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS devices_account_idx ON devices(account_id);

-- Only SHA-256 of bearer tokens is stored.
CREATE TABLE IF NOT EXISTS tokens (
    hash        text PRIMARY KEY,
    device_id   text NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    account_id  text NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS tokens_device_idx ON tokens(device_id);

-- A stream is a personal profile stream (owner_account set) or a space (owner_account NULL).
CREATE TABLE IF NOT EXISTS streams (
    id             text PRIMARY KEY,
    kind           text NOT NULL CHECK (kind IN ('personal', 'space')),
    owner_account  text REFERENCES accounts(id) ON DELETE CASCADE,
    created_at     timestamptz NOT NULL,
    CHECK ((kind = 'personal') = (owner_account IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS streams_owner_idx ON streams(owner_account);

-- Cursor is per stream: max(cursor)+1 assigned while holding the stream row lock.
-- device is not a foreign key so history survives device sign-out.
CREATE TABLE IF NOT EXISTS events (
    stream_id   text NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    cursor      bigint NOT NULL,
    blob        bytea NOT NULL,
    device      text NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (stream_id, cursor)
);

CREATE TABLE IF NOT EXISTS stream_keys (
    stream_id   text NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    account_id  text NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    wrapped     text NOT NULL,
    kind        text NOT NULL,
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (stream_id, account_id)
);
CREATE INDEX IF NOT EXISTS stream_keys_account_idx ON stream_keys(account_id);

CREATE TABLE IF NOT EXISTS spaces (
    id          text PRIMARY KEY REFERENCES streams(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS members (
    space_id    text NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    account_id  text NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    role        text NOT NULL CHECK (role IN ('OWNER','ADMIN','EDITOR','CONTRIBUTOR','VIEWER','NONE')),
    public_key  text NOT NULL,
    joined_at   timestamptz NOT NULL,
    left_space  boolean NOT NULL DEFAULT false,
    PRIMARY KEY (space_id, account_id)
);
CREATE INDEX IF NOT EXISTS members_account_idx ON members(account_id);

-- Only SHA-256 of invite tokens is stored.
CREATE TABLE IF NOT EXISTS invites (
    token_hash          text PRIMARY KEY,
    space_id            text NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    role                text NOT NULL,
    summary             text NOT NULL,
    inviter_account     text NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    inviter_public_key  text NOT NULL,
    expires_at          timestamptz NOT NULL,
    created_at          timestamptz NOT NULL,
    revoked             boolean NOT NULL DEFAULT false,
    accepted_by         text,
    accepted_at         timestamptz
);
CREATE INDEX IF NOT EXISTS invites_space_idx ON invites(space_id);

CREATE TABLE IF NOT EXISTS advisor_links (
    id          text PRIMARY KEY,
    account_id  text NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    blob        bytea NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS advisor_links_account_idx ON advisor_links(account_id);
