# CLAUDE.md — working agreement for the Pouch codebase

You are building Pouch, a global, offline-first, end-to-end-encrypted money tracker. The full specification is `docs/SPEC.md` in this folder. Read `docs/SPEC.md` §0, §1, §2, §4.2, §5, §6, §7, §9, §10 before writing any code, then begin Milestone M0 (§11).

## Rules that override everything else
1. `docs/SPEC.md` is the source of truth. If something is missing, choose the option that keeps the feature free, works offline, stores less personal data and is undoable. Log the decision in `DECISIONS.md`.
2. No feature is done until its row in the CRUD and lifecycle matrix (`docs/SPEC.md` §10.2) is fully implemented and tested. Creating something without edit, delete, restore and history is a defect, not a partial.
3. Nothing is hard-coded to a country, currency, language, pay cycle, bank or payment app. Regional behaviour lives in `/packs/*.json` and is loaded as data.
4. Money is integer minor units. Never use floating point for amounts. Period math must have property-based tests across every cycle type, DST transitions and month ends.
5. Every mutation is an append-only Event. Materialised tables are rebuilt from events. Undo and history come for free from this; do not bypass it.
6. Never request `READ_SMS`. Capture uses the notification listener (Android), share sheet, email forwarding, OCR, voice, imports and regulated bank connectors.
7. No ads, no lending or credit products, no data sale, no third-party ad or tracking SDKs. Analytics events carry counts only, never amounts, payees or notes.
8. Every list needs designed empty, loading, error and offline states. Destructive actions execute with a 5-second undo instead of a confirmation dialog, except deleting a profile or account.
9. Accessibility (WCAG 2.2 AA), dark mode, RTL and 200% text size are part of each screen's definition of done, not a later pass.
10. Keep the app small: budgets are in `docs/SPEC.md` §8. Language, OCR and voice packs download on demand.

## Stack
Flutter (Dart) app with Riverpod and freezed; Drift over SQLite with SQLCipher; FTS5 search; libsodium for E2EE; Go services with Postgres and S3-compatible blobs for the relay and connectors; monorepo layout `/app`, `/server`, `/packs`, `/importers`, `/docs`.

## How to work
- Start each milestone by listing the requirement IDs it closes and adding failing tests for their acceptance criteria.
- One PR per requirement cluster; PR description links the IDs and shows the CRUD matrix row status.
- Run `flutter analyze`, unit, golden (light/dark/RTL/large-text), pseudo-locale and the period-math property suite before every commit.
- Write user-facing strings in `app/lib/l10n/*.arb` with ICU plurals; never inline text in widgets.
- When touching money display, test JPY (0 decimals), KWD (3 decimals), INR with lakh grouping, and an RTL locale.
- Before marking any screen done, walk `docs/SPEC.md` §2 and confirm none of the 40 audit gaps apply to it.

## Naming
Working name is Pouch. Keep brand strings in one file (`app/lib/brand.dart`) so a rename is one change. Do not use the name "Ledger".
