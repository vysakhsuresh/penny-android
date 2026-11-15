# Penny sync relay

A small Go service that stores and forwards end-to-end encrypted event blobs between a
person's devices and the members of a shared space. It is a dumb relay: it never receives
keys or plaintext, and it cannot read anyone's money data. The one exception is the optional
bank-connector service (Plus), which fetches from a bank-data provider on the person's behalf
and passes the result straight through without storing it (see Privacy below). Forwarded
receipt emails are sealed to the person's device key the moment they arrive, so the stored
copy is ciphertext too.

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
| – | `MAIL_DOMAIN` | `in.penny.example` | Domain of forwarding addresses (point its MX at your inbound-mail provider) |
| – | `MAIL_WEBHOOK_SECRET` | – | Shared secret the mail provider sends in `X-Penny-Webhook`. Unset: the inbound webhook answers 503 (open without a secret only in `-dev`) |
| – | `MERCHANT_SALT` | random (memory/dev only) | HMAC key for merchant-vote contributor hashes; required with Postgres outside dev, keep stable |

Data residency is by deployment: run one instance (and one database) per region.
The server shuts down gracefully on SIGINT/SIGTERM and purges expired codes, advisor links,
old invites and pending mail older than 14 days every 10 minutes.

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
| GET | `/v1/mail/address` | Bearer | `{address, keyAlg}`; 404 if none issued |
| POST | `/v1/mail/address` | Bearer | `{rotate?}` (body optional) → `{address, keyAlg}`; creates on first call, same address after; `rotate:true` issues a new one (old stops at once) |
| DELETE | `/v1/mail/address` | Bearer | Turn forwarding off: delete address, key and pending mail → 204 |
| PUT | `/v1/mail/key` | Bearer | `{p256}` or `{x25519}` (exactly one) → 204 |
| GET | `/v1/mail/pending` | Bearer | `[{id, alg, blob, receivedAt, expiresAt}]`, oldest first, max 50 |
| DELETE | `/v1/mail/{id}` | Bearer | Delete after parsing → 204 (404 unknown) |
| POST | `/v1/mail/inbound` | `X-Penny-Webhook` | Provider webhook (JSON, multipart or urlencoded `{to, from, subject, text, html}`, ≤2 MB) → 204; 404 unknown address |
| POST | `/v1/merchants/suggest` | Bearer | `{region, descriptors:[≤200]}` → `[{descriptor, name, categoryKey, confidence}]` (known ones only) |
| POST | `/v1/merchants/contribute` | Bearer | `{region, items:[{descriptor, name, categoryKey}] (1–100)}` → `{accepted}`; 100 items/day/account |

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

### Email forwarding (CP-06)

1. The app calls `POST /v1/mail/address` → `{address:"k3m9q2xwpa@in.penny.example", keyAlg:""}`.
   The local part is 10 characters from `a–z2–7` (50 random bits). Matching is
   case-insensitive and ignores a `+tag`.
2. The app uploads a mail public key with `PUT /v1/mail/key`. Use **`p256`** (works on every
   Android version via `KeyAgreement("ECDH")`); `x25519` is accepted for clients on API 31+.
   - `p256`: base64url (no padding) of the 65-byte uncompressed point `0x04 || X(32) || Y(32)`.
   - `x25519`: base64url of the raw 32-byte public key (RFC 7748 u-coordinate, little-endian).
   The key is per account (last upload wins; the uploading device id is recorded). Keep the
   private key in the Android Keystore or wrapped by it, and share it with the person's other
   devices through the encrypted sync stream if they should read mail too.
3. The mail provider posts each message to `POST /v1/mail/inbound` with header
   `X-Penny-Webhook: <MAIL_WEBHOOK_SECRET>` (missing → 401, wrong → 403). Fields `to`, `from`,
   `subject`, `text`, `html` as JSON (case-insensitive keys), `multipart/form-data` or
   `application/x-www-form-urlencoded`. If `text` is blank the server strips `html` to text
   (drops comments/`script`/`style`/`head`, block tags become line breaks, entities decoded,
   whitespace collapsed). Attachments are ignored.
4. The server builds `{"from":…, "subject":…, "text":…, "receivedAt":<epoch ms>}` (UTF-8 JSON;
   `text` ≤ 64 KiB, `subject` ≤ 1 KiB, `from` ≤ 512 bytes, cut on character boundaries), seals it
   (below) and stores only the blob. No key yet → the mail is discarded (204, counted). Unknown
   address → 404 `{"error":"not found"}`. At most 50 pending per account; the oldest are dropped.
5. The app polls `GET /v1/mail/pending`, decrypts, parses, then `DELETE /v1/mail/{id}`.
   Unfetched mail expires 14 days after `receivedAt`.

**Sealing scheme `penny-mail-v1`** (ECIES; `alg` on each pending item says which curve):

| Step | P-256 (`alg:"p256"`) | X25519 (`alg:"x25519"`) |
|---|---|---|
| Ephemeral key | fresh P-256 key pair; `ephPub` = 65-byte uncompressed `0x04‖X‖Y` | fresh X25519 key pair; `ephPub` = 32 bytes |
| Shared secret `Z` | ECDH = 32-byte big-endian X coordinate (what `KeyAgreement("ECDH").generateSecret()` returns) | X25519, 32 bytes |
| `salt` | `ephPub(65) ‖ recipientPub(65)` = 130 bytes | `ephPub(32) ‖ recipientPub(32)` = 64 bytes |
| Key | `HKDF-SHA256(IKM=Z, salt, info="penny-mail-v1" (ASCII, 13 bytes), L=32)` (RFC 5869 extract+expand) | same |
| Cipher | AES-256-GCM, 12-byte random nonce, 128-bit tag, AAD = `"penny-mail-v1"` (ASCII) | same |
| `blob` (base64url, no padding) | `ephPub(65) ‖ nonce(12) ‖ ciphertext ‖ tag(16)` | `ephPub(32) ‖ nonce(12) ‖ ciphertext ‖ tag(16)` |

`recipientPub` is exactly the bytes uploaded with `PUT /v1/mail/key`. Android decrypt (P-256):
decode the blob; build the ephemeral `ECPublicKey` from bytes 1–32 (X) and 33–64 (Y) with
`ECPublicKeySpec(ECPoint(BigInteger(1,x), BigInteger(1,y)), ownKey.params)`;
`KeyAgreement.getInstance("ECDH")` with the private key → `Z`; HKDF-SHA256 with `Mac("HmacSHA256")`
(PRK = HMAC(key=salt, Z); OKM = HMAC(key=PRK, info ‖ 0x01), 32 bytes); then
`Cipher("AES/GCM/NoPadding")` with `GCMParameterSpec(128, nonce)`, `updateAAD("penny-mail-v1")`,
`doFinal(ciphertext ‖ tag)`. The reference implementation is `sealMail`/`openMail` in `mail.go`;
`TestInboundSealsToP256` decrypts by following this table step by step.

### Community merchant map (PY-03)

Opt-in clients send corrections (`descriptor` → clean `name` and `categoryKey`) per region and
ask for suggestions. Nothing is seeded.

- **Normalisation** (server side, applied to both endpoints; clients should apply it before
  contributing): lowercase; delete `'` and `’`; every character that is not a letter, digit,
  combining mark or `&` becomes a space; delete runs of 4+ digits; split on whitespace; drop the
  tokens `pos upi ref txn trx ach imps neft rtgs vpa ecom pymt`; join with single spaces.
  `"UPI/SWIGGY/123456789/ref"` → `"swiggy"`, `"7-ELEVEN 12345"` → `"7 eleven"`.
- **Contribute validation** (whole request 400 naming the item index): `region` is two letters
  (pack region, case-insensitive); 1–100 items; raw `descriptor` ≤ 64 characters and non-empty
  after normalisation; `name` 1–48 characters after collapsing whitespace; `categoryKey` matches
  `^[a-z]+(\.[a-z]+)?$`; neither descriptor nor name may contain something email-like, an
  IBAN-like token, or 8+ digits in a run that may be separated by spaces, `-`, `.`, `(`, `)` or
  `+` (phone, card and account numbers). 100 items per account per day (token bucket, in memory;
  429 with `Retry-After`).
- **Votes**: one per (region, normalised descriptor, contributor); a later contribution replaces
  the earlier one (corrections). Candidates are grouped by case-folded name and category.
- **Visibility (k-anonymity)**: a descriptor is suggested only when its leading candidate has
  ≥ 3 distinct contributors **and** ≥ 60% of the descriptor's votes. `confidence` is that vote
  share rounded to two decimals (0.6–1.0). `descriptor` in the answer echoes the string sent.
  Suggest accepts ≤ 200 descriptors (longer than 64 characters are skipped) and is limited to
  60 requests/minute/account.

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

**Email forwarding:**
- The forwarding address's local part, the account it routes to, and the mail public key (and
  which device uploaded it).
- A forwarded email exists in plaintext **only in server memory during the inbound webhook
  request**: it is sealed to the account's public key before anything is stored, and the
  marshalled plaintext buffer is zeroed after sealing (Go strings from the request cannot be
  wiped; they become garbage when the request ends). Only the ciphertext blob, its id, the
  algorithm and the receive time are stored, until the device deletes it or 14 days pass. The
  server cannot decrypt stored mail. Mail for an account without a key is discarded unstored.
- Logs never carry senders, subjects, bodies or addresses; inbound logs show only blob size and
  how many old messages were dropped. Outcome counters (stored, discarded without key, unknown
  address, dropped over cap) are kept in memory (`mailStats`); there is no metrics endpoint yet.
- The mail provider in front of the webhook sees mail in transit; pick one with a no-retention
  setting.

**Community merchant map:**
- Receives descriptors, names and categories only — never amounts, dates, accounts or notes.
  Stores the normalised descriptor (long digit runs removed), the name, the category, the
  region and a contributor hash: HMAC-SHA256 under `MERCHANT_SALT` of account id, region and
  descriptor. The hash dedupes one account's vote per descriptor but differs per descriptor, so
  stored votes cannot be grouped into one person's shopping list, and the table has no link to
  accounts.
- Mappings are revealed only with ≥ 3 agreeing contributors. Suggest requests are not stored.
- Votes are anonymous aggregates and **stay after account deletion**: they cannot be found by
  account without the deleted account id and the salt, and removing them would require keeping
  exactly the link the design avoids.

**Stores only hashes of secrets:** sign-in codes, bearer tokens and invite tokens are stored
as SHA-256; raw values are shown once.

**Logs** carry the route pattern (never the raw path, so tokens and ids in URLs are not
logged), status, size and duration. Emails appear only as a 12-character hash prefix. Codes,
tokens and blobs are never logged. In `-dev` mode the code is printed to stdout for local
testing only.

**Deletion:** `DELETE /v1/account` removes the account, devices, tokens, personal streams and
their events, keys, memberships, invites it created, advisor links, bank connections (after
revoking each at its provider, best-effort), connect states, the Plus entitlement, the mail
forwarding address and key, and pending mail in the same request. Anonymous merchant votes
stay (see above). Expired codes, advisor links, connect states and pending mail older than 14
days are purged every 10 minutes.

## Storage notes

- Event blobs are stored in Postgres as `bytea`. An S3-compatible blob store (for large
  attachments such as receipt images) is not needed yet and is not implemented.
- Schema: [`migrations/001_init.sql`](migrations/001_init.sql),
  [`migrations/002_connectors.sql`](migrations/002_connectors.sql) and
  [`migrations/003_mail_merchants.sql`](migrations/003_mail_merchants.sql). Foreign keys cascade, so
  deleting an account row removes everything it owns.
- `CONNECTOR_KEY` rotation is not implemented: with a new key, existing connections report
  `needs_consent` on their next sync and recover through re-consent.
- Two concurrent syncs of one connection can both deliver the same new transactions (last
  cursor write wins); clients upsert by id, so this only costs bandwidth.
- Rate limits (merchant contributions included) are per process and reset on restart; behind
  several replicas each replica has its own budget.
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
| `mail.go` | Email forwarding: addresses, mail key, inbound webhook, `penny-mail-v1` sealing, HTML stripping |
| `merchants.go` | Community merchant map: normalisation, validation, votes, k-anonymous suggestions |
| `connectors/` | `Connector` interface, registry, exact decimal parsing, SimpleFIN, sandbox, provider stubs |
| `api_test.go` | httptest suite |
| `connections_test.go` | Connector suite with the sandbox and a fake SimpleFIN bridge |
| `mail_test.go`, `merchants_test.go` | Mail forwarding (decrypting per the documented layout) and merchant map suites |
