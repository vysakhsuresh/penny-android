# M0 handoff (resume here)

Branch: `m0-foundations` (off `ccr-f52afb8c-io9kez`). User merges to main.

## Done and green
- Gradle skeleton: version catalog, wrapper 8.14.3, AGP 8.13.2, Kotlin 2.2.21, KSP 2.2.21-2.0.5, detekt config.
- `core` (pure Kotlin), 35 Kotest tests green (`gradlew :core:test`):
  - money: `Money`, `CurrencyUnit` (ISO minor units), `NumberFormatSpec` (locale separators, symbol position, Latin digits),
    `MoneyFormatter` (STANDARD / INDIAN lakh grouping), `AmountCalculator` (TX-08, TX-09).
  - period: `CycleRule` (monthly, semimonthly, weekly, biweekly, fourweekly, quarterly, yearly, onincome), `Periods`
    (periodFor/next/previous/shift/bounds/progress), property tests incl. DST and month ends.
  - event: `Event`, `Projector`, `EventStore` + `InMemoryEventStore`, `EventSourcedRepository` (create, update diff,
    delete, restore, purge, undo via compensating events, history, revertTo).
  - model: `Profile`, `FinancialAccount`, `Category`, `Transaction` (all TX-02 fields), `TransactionValidator`,
    `DefaultCategories`; budget: `Ledger` (balances), `SafeToSpendCalculator` (BU-07, AL-02 pace).
- `packs/src/main/assets/packs/*.json`: 14 packs (IN PH US GB DE BR MX AE JP ID NG AU SG XX), Appendix B schema,
  values from `prototype/packs.js`. `number_grouping` is `standard` or `indian`; `pay_cycle_default` matches `CycleRule` JSON.

## Next steps
1. `packs` module: Android library (`app.penny.packs`), `build.gradle.kts`, `RegionalPack` @Serializable model,
   `PackLoader` (reads `assets/packs/*.json` through AssetManager; pure `parse(json)` for unit tests),
   Kotest test that loads every file from `src/main/assets/packs` and checks currency, grouping and cycle.
2. `data` module: Room + SQLCipher (`net.zetetic:sqlcipher-android`, `SupportOpenHelperFactory`), tables:
   `events` (append-only), `entities` (generic projection: entity, id, profileId, json, version, createdAt, updatedAt,
   deletedAt, lastActor), `transactions` (materialised columns for queries) + FTS4 on payee/note.
   `RoomEventStore : EventStore` appends and projects in one DB transaction. DB key: random 32 bytes wrapped by an
   AndroidKeyStore AES-GCM key and stored in noBackupFilesDir. Hilt module.
3. `app` module: Hilt app, Compose M3 theme (one accent, amber pace, never red), app label from `Brand.NAME` via
   `resValue` parsed from Brand.kt, strings in `res/values/strings.xml` with plurals, `supportsRtl`, pseudo-locales on
   in debug. Screens: Onboarding (region, pay cycle, cash account; each skippable), Home (safe-to-spend card,
   period prev/next, accounts strip, transactions list with empty/loading/error states, swipe delete + 5 s undo
   snackbar), Transaction form (create/edit every field, calculator amount, date/time pickers, delete with undo),
   History (versions, diffs, revert), Trash (restore, purge), Settings (switch regional pack: currency decimals,
   grouping and cycle change with no code change).
4. Docs: `DECISIONS.md` (record: targetSdk 36 for now with AGP 8.13; OnIncome rolls over after maxDays; ISO minor
   units used for IDR; undo of create = purge; purge redaction of event payloads deferred to M2 compaction;
   splits/attachments UI in M1), `docs/CRUD-MATRIX.md` (Transaction row status), `.github/pull_request_template.md`
   referencing it, `.gitattributes` (`gradlew text eol=lf`).
5. Verify: `gradlew lint detekt test assembleDebug`, install on the phone (`adb devices` must list it; enable USB
   debugging and accept the prompt), and an API 26 emulator (`sdkmanager "system-images;android-26;google_apis;x86_64"`).
