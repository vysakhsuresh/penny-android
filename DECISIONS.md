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
