# Decisions

Decisions not covered by `docs/SPEC.md`, chosen by the rule in SPEC §0.7 (free, offline, less data, undoable).

## 2026-11-15 (M0)

1. **targetSdk 36, compileSdk 36.** AGP 8.13.2 is the newest plugin cached and verified with Kotlin 2.2.21 + KSP2 here; it is tested up to API 36. Raise to API 37 together with an AGP upgrade in M1.
2. **"When money lands" never leaves a date without a period.** A period starts on each qualifying income date; if no income lands for `maxDays` (default 31), a new period starts anyway. Dates before the first income are split into `maxDays` periods counted back from it. With no income at all the cycle falls back to monthly from day 1. Keeps every transaction in exactly one period (§6 invariant 3).
3. **Day 31 means "last day of the month".** Start days are clamped to the month length, so "15 and last" is stored as `SemiMonthly(15, 31)`.
4. **Minor units from ISO 4217** (`java.util.Currency`), e.g. IDR has 2 even though the prototype showed 0. A display-decimals override per profile (ST-01) comes in M1.
5. **Undo of a create purges** the entity (the user never meant to create it); every other undo is a compensating event (update with the old values, restore or delete). The log is never edited.
6. **Purge** removes the materialised row now. Redacting earlier event payloads for a purged entity happens at log compaction, which arrives with sync in M2.
7. **Delete forever is delayed 5 s** behind an undo snackbar instead of a confirmation dialog (UX-02).
8. **Revert** restores data fields only; whether the entity is deleted is left as it is.
9. **Region switch** updates base currency, locale, grouping, pay cycle, week start and fiscal year from the pack. Accounts with no transactions switch currency too; accounts with history and all transactions keep their currency. Undo restores everything.
10. **Amounts render inside Unicode first-strong isolates** so RTL symbols (AED) and LTR text never reorder each other.
11. **Digits are always Latin** in money display, with locale separators. Native digit display is a later setting.
12. **Splits, attachments, location and linked objects** exist in the Transaction model and survive edits; their editors are M1 (TX-07, TX-11).
13. **No login in M0**: local-only mode (US-01) with a per-install account id and device id stamped on every event.
14. **Category names are seeded in English** as user data, so they can be renamed. Localised default names come from packs in M1 (CAT-02).
15. **The app has no permissions at all in M0**, not even INTERNET.

## 2026-11-15/16 (M1–M6)

16. **One generic entity projection.** Every object (tags, envelopes, goals, people, spaces…) is an event-sourced entity in the same `entities` table; only transactions also get a query-friendly table. Adding an object type needs no migration.
17. **Lamport clock per event (`vclock["l"]`, `events.lamport` column, migration v2).** Projection replays an entity's events ordered by (lamport, device, id), giving field-level last-writer-wins that converges on every device. A concurrent edit is detected when an UPDATE's `before` value does not match the state it lands on; both versions stay in history and the History screen marks the conflict.
18. **Erasure beats append-only.** Deleting a profile or "delete everything" removes that profile's events from this device (SE-09). It is the only operation that edits the log.
19. **A space is a shared book.** A space's stream id is also a profile id with its own Profile entity, so every screen works unchanged inside a space. Roles are enforced client-side for content (the relay sees only ciphertext) and server-side for membership (viewers cannot push).
20. **Keys.** Stream keys are random AES-256; they are wrapped by a master key derived from a 24-word recovery phrase (PBKDF2-HMAC-SHA256, 200k iterations, per-account salt) and, for spaces, to each member device's X25519 HPKE key (Tink). The same phrase encrypts folder backups. The phrase uses a 256-word list (192 bits) rather than BIP-39 to keep the app small.
21. **Relay in Go with an in-memory and a Postgres store.** Postgres code compiles and follows the same interface but has not yet run against a database; tests use the memory store. Attachments are not uploaded yet (stored on device only); S3 blob storage is deferred until attachment sync.
22. **Bank sync runs on the relay's connector service** (SE-08). SimpleFIN is implemented end to end; the other 17 providers are stubs that name the API and credentials they need, because each requires a commercial contract. Transactions pass through the server; only a cursor is kept.
23. **Plus entitlement is not yet verified with Google Play** on the server (`verifyPlayPurchase` TODO). Must be done before launch; until then any purchase token grants Plus on the relay.
24. **Email forwarding is encrypted on arrival.** The relay encrypts each forwarded mail to the account's P-256 key (ECDH + HKDF + AES-GCM, "penny-mail-v1") before storing it; the phone decrypts and parses. P-256 instead of X25519 because XDH needs Android 12+.
25. **Community merchant map is opt-in and k-anonymous**: a name/category mapping is shown only after 3 different accounts agree with ≥ 60% of votes; contributions never include amounts and long digit runs are stripped first.
26. **Automation API is on the device.** Because data is end-to-end encrypted, third-party automation uses an add-only intent and user-owned webhooks (HMAC-signed, https only) instead of a cloud API.
27. **Exchange rates** come from the ECB reference feed on Wi-Fi, cached 400 days outside the event log; manual rates are events and win for the same date.
28. **Usage counts** are local, opt-in and counts only; there is no upload endpoint yet and no third-party SDK.
29. **Placeholder brand addresses** use the reserved `.example` domain (`support@penny.example`, `relay.penny.example`) until real ones exist; they are all in `Brand.kt`.
30. **Typeface: Plus Jakarta Sans** (OFL, bundled variable font, 176 KB) — chosen to be distinct from LayerLink's Space Grotesk and for its tabular figures.
31. **Chart colours** use the validated reference categorical hues (blue, aqua, magenta, green, violet); orange, yellow and red slots are excluded because amber/orange are status colours and red is never used for money. At most five series plus "Other", always with labels or a table view.
32. **Wear OS app shares the phone app id** and talks only to the paired phone over the Data Layer; it stores nothing but the last summary.
33. **Translations are machine drafts** in 30 languages (LO-01 allows this before native review). Strings added after the translation pass fall back to English until the next pass.
34. **Trash auto-purges after 30 days** in the daily worker; purge before that waits behind the 5-second undo.

### 2026-11-16 — system bug sweep
35. **The app lock is an overlay above navigation**, not a replacement for it, so locking never loses the back stack, a half-filled form or a pending camera/file result. Unlock state is process-scoped: after process death Penny always starts locked. PIN back-off (failures and lockout end) is persisted.
36. **Failed writes never show success.** `act` and the transaction form report "That did not save" and keep the user on the screen; a second tap while a save is running is ignored.
37. **Undo expiry runs in the app-scoped controller**, not the snackbar, so "delete forever" happens even if the screen rotates or the app is backgrounded during the 5 seconds; exactly one of undo or expiry runs.
38. **Scheduled posting is idempotent**: one run at a time, deterministic ids (`<occurrence>#tx`, `auto:<goal>:<period>`), and existence checked including deleted rows, so a bill the user deleted or reopened is never posted again. The daily worker covers every profile, not only the open one.
39. **Dedupe**: two records from the same source with different external ids (two bank rows, two notifications) are two payments; only different sources fall back to fuzzy matching. Notification keys use the notification's own timestamp so re-posts match.
40. **Sync never skips a batch it cannot read**: the cursor stops before it and sync reports an error, so an app update can read it later. One failing stream does not stop the others. Leaving a space or deleting a profile drops its key and cursors.
41. **Restoring your own export marks events as local**, so they are pushed when sync is turned on.
42. **Clock stamping happens inside the store lock and transaction**, so commit order equals Lamport order on each device.
43. **Totals across currencies are converted** (safe-to-spend bills and goals, Activity "spent", coach answers); amounts without a rate are left out and counted, never added as raw numbers. Split lines and excluded categories follow the same rules everywhere.
44. **Deleting a people record removes what it posted** (remittance, lending entry, group expense, circle contribution) in the same undo group; editing it keeps the posted transaction in step.
45. **Backups fail instead of falling back to plaintext** when encryption is on but the key cannot be unwrapped.
46. **The relay never receives a space name**; invites carry an empty summary.
47. **Shared bank files go to the importer** through a one-shot cache file; PDF sharing was removed because there is no PDF statement parser.
48. **Due dates may use a muted red** (2026-11-17): "never red" still holds for spending and alerts, but a bill due today or overdue is shown in a muted red, amber inside its reminder window and green once paid, because the owner asked for urgency to read at a glance. Colour is never the only signal; the text says "Due today" or "Overdue".
49. **"Envelope" is shown as "spending limit"** (2026-11-17) in English copy; code and data keep the envelope name. An info button explains the idea in plain words.
