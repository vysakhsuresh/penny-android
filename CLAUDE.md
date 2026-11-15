# CLAUDE.md — working agreement for the penny-android codebase

You are building Penny, a global, offline-first, end-to-end-encrypted money tracker, as a native Android app. The full specification is `docs/SPEC.md`. The kickoff prompt and milestone order are in `docs/KICKOFF.md`. The interactive behaviour reference is `prototype/index.html` (open it in a browser; it also lists the 40 audit gaps that must never recur).

Read `docs/SPEC.md` §0, §1, §2, §3, §4.2, §5, §6, §7, §9, §10 and §11 before writing any code, then begin Milestone M0.

## Rules that override everything else
1. `docs/SPEC.md` is the source of truth. If something is missing, choose the option that keeps the feature free, works offline, stores less personal data and is undoable. Log the decision in `DECISIONS.md` with the date.
2. No feature is done until its row in the CRUD and lifecycle matrix (`docs/SPEC.md` §10.2) is fully implemented and tested. Creating something without edit, delete, restore and history is a defect, not a partial.
3. Nothing is hard-coded to a country, currency, language, pay cycle, bank or payment app. Regional behaviour lives in `packs/src/main/assets/packs/*.json` and is loaded as data. Start from the values in `prototype/packs.js`.
4. Money is integer minor units (`Long`). Never use floating point for amounts. Period math must have property-based tests (Kotest) across every cycle type, DST transitions and month ends.
5. Every mutation is an append-only Event row. Materialised tables are rebuilt from events. Undo and history come from this; do not bypass it.
6. Never request `READ_SMS`. Capture uses `NotificationListenerService`, the share sheet, email forwarding, on-device OCR (ML Kit), on-device speech, imports and regulated bank connectors.
7. No ads, no lending or credit products, no data sale, no third-party ad or tracking SDKs. Analytics events carry counts only, never amounts, payees or notes.
8. Every list needs designed empty, loading, error and offline states. Destructive actions execute with a 5-second undo snackbar instead of a confirmation dialog, except deleting a profile or account (typed confirmation).
9. Accessibility (TalkBack labels, 48 dp targets, WCAG 2.2 AA contrast), dark mode, RTL and 200% font scale are part of each screen's definition of done, not a later pass.
10. Keep the app small: budgets are in `docs/SPEC.md` §8. Language, OCR and voice packs download on demand (Play Feature Delivery or on-demand assets).

## Stack (this repository)
- Kotlin, Jetpack Compose with Material 3, minSdk 26, targetSdk latest stable, Gradle Kotlin DSL with a version catalog.
- Modules: `core` (pure Kotlin: money, dates, periods, rules, event log, alerts; no Android dependencies), `data` (Room + SQLCipher, append-only `events` table, materialised views, FTS4/5 search), `packs` (regional JSON assets + loader), `capture` (notification parsers, share intake, OCR, voice), `sync` (libsodium/Tink E2EE, relay client), `app` (Compose UI, navigation, widgets).
- DI with Hilt. Coroutines and Flow. Kotest + Turbine for tests; Compose UI tests and screenshot tests (light, dark, RTL, 200% font) in CI.
- Crypto: Tink or libsodium-jni; keys in Android Keystore; SQLCipher key wrapped by the Keystore key; E2EE sync keys per profile and per space.
- Backend (later milestones): Go services with Postgres and S3-compatible blobs, regions US, EU, IN, SG. Not part of M0–M1.

## How to work
- Start each milestone by listing the requirement IDs it closes and adding failing tests for their acceptance criteria.
- One PR per requirement cluster; the PR description links the IDs and shows the CRUD matrix row status (`docs/CRUD-MATRIX.md`).
- Run `./gradlew lint detekt test` and the Compose screenshot suite before every commit.
- Strings live in `res/values*/strings.xml` with ICU plurals; never inline text in composables. Numbers and currency through `core` formatters only.
- When touching money display, test JPY (0 decimals), KWD (3 decimals), INR with lakh grouping and an RTL locale.
- Before marking any screen done, walk `docs/SPEC.md` §2 and confirm none of the 40 audit gaps apply to it.
- Commit after each green step with clear messages. Do not include model names in commits.

## Naming
Product name is Penny. Keep brand strings in one file (`core/src/main/kotlin/app/penny/core/Brand.kt`) so a rename is one change. Do not use the names Ledger, Pouch or Saldo anywhere.
