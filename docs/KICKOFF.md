# Kickoff for the local build session (vysakhsuresh-virtual-rivest)

Project folder on the laptop: `C:\Users\vysak\StudioProjects\penny-android`
Repository: `github.com/vysakhsuresh/penny-android`, branch `ccr-f52afb8c-io9kez` (merge to `main` when you are happy).

## Step 0: get the files

```powershell
cd C:\Users\vysak\StudioProjects\penny-android
git fetch origin
git checkout ccr-f52afb8c-io9kez
```

You now have `CLAUDE.md` (rules), `docs/SPEC.md` (the specification) and `prototype/` (open `prototype/index.html` in a browser; it is the behaviour reference, with a 40-gap checklist in the side panel).

## Step 1: the prompt to paste into Claude Code in that folder

> Read CLAUDE.md, then docs/SPEC.md sections 0, 1, 2, 3, 4.2, 5, 6, 7, 9, 10 and 11. Open prototype/index.html in a browser and click through Home, quick add, a transaction edit, Budgets, Accounts, Profiles and the 40-gap checklist so you know the expected behaviour. Then start Milestone M0 exactly as SPEC.md §11 defines it: create the Android project in this folder (Kotlin, Jetpack Compose, minSdk 26, targetSdk latest stable, Gradle version catalog), a pure-Kotlin `core` module for money, dates, periods, rules and the event log, a `data` module with Room + SQLCipher and an append-only event table, a `packs` module that loads regional packs from JSON assets (start with IN, PH, US, GB, DE, BR, AE, JP, ID, NG, MX, AU, SG, XX using the data in prototype/packs.js), and an `app` module with the design system and the first three screens: onboarding, Home with safe-to-spend, and the transaction form with the full CRUD matrix row for Transaction (create, read, update every field, soft delete, restore, history). Before any screen is called done, walk SPEC.md §2 and confirm none of the 40 audit gaps apply. Write property-based tests for period math across every cycle type, DST and month ends, and unit tests for money with JPY, KWD and INR lakh grouping. Keep strings in resources with ICU plurals, support RTL, and make every mutation an Event. Commit after each green step with clear messages. Do not add ads, analytics SDKs, READ_SMS, or any lending feature.

## Step 2: what "done" means for M0 (from SPEC.md §11)

- Project builds and runs on an API 26 emulator and a physical phone.
- Period math property tests green for monthly, semi-monthly, weekly, biweekly, four-weekly, quarterly, yearly and "when money lands".
- Add, edit, delete, restore and view history of a transaction, fully offline.
- Regional pack switch changes currency decimals, number grouping and pay-cycle default without code changes.
- CRUD matrix tool scaffolded (a checklist in `docs/CRUD-MATRIX.md` that the PR template references).

## Step 3: order of milestones after M0

M1 Core → M2 People and sync → M3 Capture → M4 Alerts, remittances, circles, widgets, 30 languages → M5 Plus and bank sync → M6 Depth. Each has exit criteria in SPEC.md §11.

## Notes for the local session

- Android-specific implementation choice: Kotlin + Compose is the native path the laptop can build today; the spec's Flutter note (§7) is superseded for this repository because the product is Android-first here and an iOS target can be revisited at M4.
- Name is Penny. Keep brand strings in one file (`core/brand.kt`).
- Never use `READ_SMS`. Capture is notification listener, share sheet, OCR, voice, import and regulated bank connectors.
- Everything free; Penny Plus is cost pass-through only (bank sync fees, storage, support).
