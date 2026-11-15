# Penny sync relay

A small Go service that stores and forwards end-to-end encrypted event blobs between a
person's devices and the members of a shared space. It is a dumb relay: it never receives
keys or plaintext, and it cannot read anyone's money data. The one exception is the optional
bank-connector service (Plus), which fetches from a bank-data provider on the person's behalf
and passes the result straight through without storing it (see Privacy below).

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
export CONNECTOR_KEY=$(openssl rand -base64 32) # keep stable: it seals bank credentials
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
| `-connectors` | `CONNECTORS` | `sandbox,simplefin` | Enabled bank connectors (comma list). Stubs listed here stay disabled with a warning |
| – | `CONNECTOR_KEY` | random (`-dev` only) | 32 bytes, base64. AES-256-GCM key for bank credentials at rest; **required outside `-dev`**, keep stable (`openssl rand -base64 32`) |

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
| GET | `/v1/connectors` | Bearer | `[{id, name, kind, regions, enabled}]`; `kind` is `redirect` or `token` |
| POST | `/v1/connections` | Bearer (Plus¹) | `{provider, redirectUri, token?}` → token kind: 201 `Connection` with `accounts`; redirect kind: 200 `{redirectUrl, state}` |
| POST | `/v1/connections/complete` | Bearer (Plus¹) | `{state, params}` (callback query params) → 201 `Connection` (200 when renewing). State is single use, 30 min |
| GET | `/v1/connections` | Bearer | `[Connection]` (no `accounts`) |
| POST | `/v1/connections/{id}/sync` | Bearer (Plus¹) | `{accounts?, since?, full?}` → `{transactions, accounts, status, message?}`; only transactions not delivered before |
| POST | `/v1/connections/{id}/reconsent` | Bearer (Plus¹) | Same body and answers as `POST /v1/connections` (provider taken from the connection); renews the same id |
| DELETE | `/v1/connections/{id}` | Bearer | Revoke at the provider (best-effort), delete credentials and cursor → 204 |
| PUT | `/v1/entitlements/plus` | Bearer | `{store:"play", purchaseToken, productId}` → `{plus, plusUntil, productId, store}` |
| GET | `/v1/entitlements/plus` | Bearer | `{plus, plusUntil, productId?, store?}` (`plusUntil` null when none) |

¹ `402 {"error":"plus_required"}` without an active entitlement, except in `-dev` mode and for
the `sandbox` provider. Listing and deleting connections never need Plus, so a lapsed
subscription can always disconnect.

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

### Bank connectors (CP-10, AC-09, SE-08)

One `connectors.Connector` interface (`connectors/`) with one adapter per provider:
`Start` → `Complete` → `Accounts` / `Transactions` → `Disconnect`. Money is signed integer
minor units (negative = money out); provider decimal strings are parsed exactly with
`connectors.ParseMinor` (ISO 4217 exponents: JPY 0, KWD 3, …; excess digits round half-even),
never through `float64`.

| Adapter | State |
|---|---|
| `simplefin` | Real. SimpleFIN Bridge protocol: the person pastes a Setup Token (kind `token`); the server base64-decodes the claim URL, POSTs to claim the Access URL, then `GET {access}/accounts?start-date=…&pending=1&account=…` with basic auth. Claim URLs must be `https` and the HTTP client only dials public IPs (the URL is user input). The protocol has no revoke call, so disconnect deletes the sealed Access URL; the person can also revoke it at their bridge |
| `sandbox` | Deterministic fake provider (kind `redirect`): `redirectUrl` is the app's `redirectUri` with `state` and `code=sandbox-consent`; two accounts (USD checking, JPY savings), 90-day consent, transactions generated per day. No Plus needed |
| `plaid`, `mx`, `truelayer`, `gocardless`, `tink`, `saltedge`, `basiq`, `akahu`, `belvo`, `pluggy`, `mono`, `okra`, `stitch`, `brankas`, `finverse`, `aa_setu`, `sgfindex` | Stubs: listed with `enabled:false`; every call returns `ErrNotConfigured` naming the env vars it will need. Each has a doc comment with the provider API it will call (`connectors/stubs.go`) |

Flows for the client:

- **Token kind** (`simplefin`): `POST /v1/connections {provider, token}` → 201 `Connection`.
- **Redirect kind**: `POST /v1/connections {provider, redirectUri}` → `{redirectUrl, state}`;
  open `redirectUrl` in a Custom Tab; when the provider redirects to `redirectUri`, post all
  query parameters to `POST /v1/connections/complete {state, params}` → `Connection`.
- **Sync**: `POST /v1/connections/{id}/sync {accounts:[remote ids]}` (omit or empty = all;
  this is the per-account toggle). Per remote account, the window starts 7 days before its last
  fetch (90 days back on the first fetch, or `since` if given, at most 2 years), and transactions
  already delivered are skipped by id hash. A pending transaction that later posts under the same
  id is delivered again with `pending:false`, so **upsert by (connection id, transaction id)**.
  `full:true` re-delivers everything in the window (after a reinstall).
- **Status** (`active`, `needs_consent`, `broken`, `disconnected`): a sync that hits a provider
  problem answers 200 with that `status`, empty lists and a `message`. `needs_consent` when the
  consent expired or the provider asks for re-authentication → `POST …/reconsent`.
  `disconnected` when the provider revoked access; the server drops the credentials and cursor.
  `broken` for other provider failures; the next good sync sets `active` again. `GET
  /v1/connections` reports `needs_consent` as soon as `consentExpiry` passes.
- **Errors on connect**: 400 bad token/params or provider not enabled, 402 `plus_required`,
  404/410 unknown or expired `state`, 409 consent required/revoked, 501 provider not configured,
  502 provider error, 504 provider timeout.

### Plus entitlement

`PUT /v1/entitlements/plus {store:"play", purchaseToken, productId}` records `plus_until`:
32 days from now for subscriptions, far future (9999-12-31) when `productId` contains
`lifetime`. A grant is never shortened. Only SHA-256 of the purchase token is stored.

> **Before launch:** the purchase is **not verified** yet; any token is accepted. Replace
> `verifyPlayPurchase` in `connections.go` with a Google Play Developer API check
> (`purchases.subscriptionsv2.get` / `purchases.products.get` with a service account, env
> `PLAY_PACKAGE_NAME` and `PLAY_SERVICE_ACCOUNT_JSON`), use the returned expiry, reject
> pending/refunded purchases, acknowledge the purchase, bind a token to one account, and handle
> Real-time Developer Notifications for renewals and revocations.

## Privacy: what the server can and cannot see

**Cannot see:** transaction amounts, payees, notes, categories, budgets, account names,
balances, wrapped stream keys' contents, advisor summaries, or the advisor link key — except
bank-connector data in transit during a sync, which is never stored.
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

**Bank connectors (the one place the server handles financial data in plaintext):**
- Provider credentials (tokens, SimpleFIN Access URLs) stay on the server and never reach the
  client (SE-08). They are stored only as AES-256-GCM ciphertext under `CONNECTOR_KEY`, which
  is not in the database; the connection id is bound in as additional data. Logs never carry
  them (provider errors are logged without request URLs).
- Transactions and balances fetched from a provider pass through memory and are returned to the
  client in the sync response over TLS; **the server does not store them**. The spec's ideal is
  to deliver them end-to-end encrypted to the device; because the client fetches them directly
  in the response and nothing is persisted, there is no stored copy to encrypt. The client
  writes them into its own encrypted event log.
- What is stored per connection: provider id, institution name, status, consent expiry, last
  sync time, and a cursor of last-fetch times per remote account id plus truncated SHA-256
  hashes of delivered transaction ids with the transaction day, pruned after 60 days (used
  only to avoid re-delivering). No amounts, payees, descriptions or balances.
- Pending redirect consents store a SHA-256 of the `state`, the redirect URI and an expiry
  (30 minutes; purged by the janitor).
- Plus: product id, store, `plus_until` and a SHA-256 of the purchase token.
- **Delete-on-disconnect:** `DELETE /v1/connections/{id}` revokes access at the provider where
  the provider supports it, then deletes the credentials, cursor and pending states. A provider
  revocation detected during sync deletes the credentials and cursor at once.

**Stores only hashes of secrets:** sign-in codes, bearer tokens and invite tokens are stored
as SHA-256; raw values are shown once.

**Logs** carry the route pattern (never the raw path, so tokens and ids in URLs are not
logged), status, size and duration. Emails appear only as a 12-character hash prefix. Codes,
tokens and blobs are never logged. In `-dev` mode the code is printed to stdout for local
testing only.

**Deletion:** `DELETE /v1/account` removes the account, devices, tokens, personal streams and
their events, keys, memberships, invites it created, advisor links, bank connections (after
revoking each at its provider, best-effort), connect states and the Plus entitlement in the same
request. Expired codes, advisor links and connect states are purged every 10 minutes.

## Storage notes

- Event blobs are stored in Postgres as `bytea`. An S3-compatible blob store (for large
  attachments such as receipt images) is not needed yet and is not implemented.
- Schema: [`migrations/001_init.sql`](migrations/001_init.sql) and
  [`migrations/002_connectors.sql`](migrations/002_connectors.sql). Foreign keys cascade, so
  deleting an account row removes everything it owns.
- `CONNECTOR_KEY` rotation is not implemented: with a new key, existing connections report
  `needs_consent` on their next sync and recover through re-consent.
- Two concurrent syncs of one connection can both deliver the same new transactions (last
  cursor write wins); clients upsert by id, so this only costs bandwidth.
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
| `connections.go` | Bank connector and Plus entitlement endpoints, sync cursor |
| `sealer.go` | AES-256-GCM sealing of connector credentials |
| `connectors/` | `Connector` interface, registry, exact decimal parsing, SimpleFIN, sandbox, provider stubs |
| `api_test.go` | httptest suite |
| `connections_test.go` | Connector suite with the sandbox and a fake SimpleFIN bridge |
