# Penny sync relay

A small Go service that stores and forwards end-to-end encrypted event blobs between a
person's devices and the members of a shared space. It is a dumb relay: it never receives
keys or plaintext, and it cannot read anyone's money data.

Standard library HTTP (Go 1.22+ routing), `log/slog`, Postgres via `pgx/v5`, and an
in-memory store for tests and local development. The API is described in
[`openapi.yaml`](openapi.yaml).

## Run

```sh
# Local dev: in-memory store, sign-in codes printed to stdout
go run . -dev

# Postgres
export DATABASE_URL=postgres://penny:penny@localhost:5432/penny?sslmode=disable
export EMAIL_PEPPER=$(openssl rand -hex 32)   # keep stable: it keys the email hash
go run . -migrate -region eu

# Tests (use the in-memory store)
go test ./...
```

Docker: `docker build -t penny-relay . && docker run -p 8080:8080 -e DEV=true penny-relay`
(multi-stage build, `distroless/static` runtime, runs as non-root).

### Configuration

| Flag | Env | Default | Meaning |
|---|---|---|---|
| `-addr` | `ADDR` | `:8080` | Listen address |
| `-database-url` | `DATABASE_URL` | – | Postgres connection URL |
| `-store` | `STORE` | `postgres` if `DATABASE_URL` set, else `memory` | `memory` or `postgres` |
| `-region` | `REGION` | `us` | `us`, `eu`, `in` or `sg`; reported in every session |
| `-migrate` | `MIGRATE` | false | Apply `migrations/*.sql` (embedded) at startup |
| `-dev` | `DEV` | false | Print sign-in codes to stdout |
| `-trust-proxy` | `TRUST_PROXY` | false | Take client IP from `X-Forwarded-For` (for rate limits) |
| – | `EMAIL_PEPPER` | random (memory/dev only) | HMAC key for email hashes; required with Postgres outside dev |
| – | `SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USER`, `SMTP_PASS`, `SMTP_FROM` | – | Send codes by SMTP |

Data residency is by deployment: run one instance (and one database) per region.
The server shuts down gracefully on SIGINT/SIGTERM and purges expired codes, advisor links and
old invites every 10 minutes.

## API

All bodies are JSON with camelCase fields. Binary values are base64url without padding
(padded input accepted). Times are epoch milliseconds. Errors are `{"error": "..."}`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/healthz` | – | Liveness |
| POST | `/v1/auth/start` | – | `{email}` → 204; emails a 6-digit code (10 min, 5 attempts). Rate-limited per email and per IP |
| POST | `/v1/auth/verify` | – | `{email, code, deviceName, platform, publicKey}` → `Session{accountId, deviceId, token, salt, region}` |
| POST | `/v1/streams/{stream}/events` | Bearer | `{blobs:[…]}` → `{cursor}`; ≤1000 blobs, ≤2 MB. VIEWER/NONE → 403 |
| GET | `/v1/streams/{stream}/events?after=N&limit=M` | Bearer | `[{cursor, blob, device}]` ordered by cursor; limit 200 default, 500 max |
| PUT | `/v1/keys/{stream}/{accountId}` | Bearer | `{stream, accountId, wrapped, kind}` → 204 |
| GET | `/v1/keys` | Bearer | All wrapped keys for the caller |
| POST | `/v1/spaces` | Bearer | `{spaceId}` → 201; caller becomes OWNER |
| POST | `/v1/spaces/{space}/invites` | Bearer (OWNER/ADMIN) | `{role, expiresInDays, summary}` → 201 `InviteInfo` |
| GET | `/v1/invites/{token}` | – | `InviteInfo` (what will be shared); 410 if expired/revoked/used |
| POST | `/v1/invites/{token}/accept` | Bearer | `{publicKey}` → `InviteInfo`; single use, max 10 active members |
| DELETE | `/v1/invites/{token}` | Bearer (OWNER/ADMIN) | Revoke → 204 |
| GET | `/v1/spaces/{space}/members` | Bearer (member) | `[{accountId, role, publicKey, joinedAt, left}]` |
| PUT | `/v1/spaces/{space}/members/{account}` | Bearer (OWNER/ADMIN) | `{role}` → 204; only OWNER grants/changes OWNER; last owner cannot be demoted (409) |
| DELETE | `/v1/spaces/{space}/members/{account}` | Bearer (OWNER/ADMIN, or self) | Remove/leave → 204; marks `left`, keeps history |
| GET | `/v1/devices` | Bearer | `[{deviceId, name, platform, lastSeen, current}]` |
| DELETE | `/v1/devices/{id}` | Bearer | Remote sign-out: revokes that device's tokens |
| DELETE | `/v1/account` | Bearer | Delete everything now; hand space ownership to the earliest admin, else earliest member |
| PUT | `/v1/advisor/{id}` | Bearer | `{blob, expiresAt}` → 204 (≤256 KiB, ≤90 days) |
| DELETE | `/v1/advisor/{id}` | Bearer | → 204 |
| GET | `/v1/advisor/{id}/blob` | – (CORS) | `{blob, expiresAt}`; 404 after expiry |
| GET | `/a/{id}#<key>` | – | Read-only advisor page; decrypts in the browser |

### Access rules

- A stream id is a personal profile stream (created on its owner's first push or key upload)
  or a space id. Personal streams: owner only. Spaces: any active member whose role is not
  `NONE` may pull; `VIEWER` may not push. Finer role rules (who may edit what) are enforced
  by clients, because content is encrypted.
- Cursors are per stream, strictly increasing and gap-free (`max+1` under a row lock).
- When a member leaves or is removed, their wrapped key for that space is deleted; events
  they pushed stay. When the last member leaves, the space and its ciphertext are deleted.

### Advisor links

The app encrypts a summary with a fresh AES-256-GCM key, uploads
`base64url(IV(12) || ciphertext+tag)`, and shares `https://<host>/a/<id>#<base64url key>`.
The fragment is never sent to the server. The page is static HTML with one inline script
(pinned by a CSP hash, `connect-src 'self'`, no external resources) that fetches the blob,
decrypts with WebCrypto and renders `{title, generatedAt, currency, rows:[{label, value}], note}`
as a table with a "Read-only · shared from Penny" watermark. Content is inserted with
`textContent` only.

## Privacy: what the server can and cannot see

**Cannot see:** transaction amounts, payees, notes, categories, budgets, account names,
balances, wrapped stream keys' contents, advisor summaries, or the advisor link key.
Everything in `events.blob`, `stream_keys.wrapped` and `advisor_links.blob` is ciphertext
produced on the device.

**Can see (metadata):**
- An HMAC-SHA256 of each email address (keyed by `EMAIL_PEPPER`), not the address. The
  address passes through memory only to send the code.
- Account, device, stream and space ids; device names and platform as sent by the client;
  device public keys.
- Event counts, sizes, timestamps and which device pushed them.
- Space membership, roles and join/leave times; invite summaries (plain text chosen by the
  inviter, so clients should keep them generic, e.g. "Household budget").
- Client IP addresses, transiently, for rate limiting (kept in memory only, never stored).

**Stores only hashes of secrets:** sign-in codes, bearer tokens and invite tokens are stored
as SHA-256; raw values are shown once.

**Logs** carry the route pattern (never the raw path, so tokens and ids in URLs are not
logged), status, size and duration. Emails appear only as a 12-character hash prefix. Codes,
tokens and blobs are never logged. In `-dev` mode the code is printed to stdout for local
testing only.

**Deletion:** `DELETE /v1/account` removes the account, devices, tokens, personal streams and
their events, keys, memberships, invites it created and advisor links in the same request.
Expired codes and advisor links are purged every 10 minutes.

## Storage notes

- Event blobs are stored in Postgres as `bytea`. An S3-compatible blob store (for large
  attachments such as receipt images) is not needed yet and is not implemented.
- Schema: [`migrations/001_init.sql`](migrations/001_init.sql). Foreign keys cascade, so
  deleting an account row removes everything it owns.
- The Postgres store compiles and is written against the schema, but the test suite runs on
  the in-memory store; run it against a real database before production.

## Layout

| File | Contents |
|---|---|
| `main.go` | Flags/env, store and mailer wiring, graceful shutdown, janitor |
| `api.go` | Router, middleware, streams, keys, spaces, invites, members, devices, account deletion |
| `auth.go` | Email-code auth, bearer middleware, mailers, rate limiter, hashing helpers |
| `advisor.go` | Advisor link endpoints and the static decrypting page |
| `store.go` | `Store` interface and models |
| `store_memory.go` | In-memory store |
| `store_postgres.go` | Postgres store and migration runner |
| `api_test.go` | httptest suite |
