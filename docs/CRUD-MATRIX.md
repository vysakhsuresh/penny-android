# CRUD and lifecycle matrix status

Source of truth for SPEC §10.2. A module is not done until its row is fully green. Every PR updates the rows it touches.

Legend: ✅ done and tested · 🟡 partial (see note) · ⬜ not started · – not applicable

| Object | Create | Read | Update all fields | Delete (soft) | Restore | Archive | Reorder | Merge | History | Bulk | Export | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Transaction | ✅ | ✅ | 🟡 | ✅ | ✅ | – | – | ⬜ dupes | ✅ | ⬜ | ⬜ | Splits, attachments and location editors in M1; every other field edits in the form. Purge from Trash with undo. History shows versions, diffs and revert. |
| Split | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | ⬜ | – | ⬜ | – | ⬜ | Model and sum validation only (M0). |
| Account | 🟡 | 🟡 | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | 🟡 | – | ⬜ | Created in onboarding; balance shown on Home; events recorded. Screen in M1. |
| Category | 🟡 | 🟡 | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 🟡 | ⬜ | ⬜ | Universal tree seeded as events; picker in the form. |
| Tag | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | ⬜ | Free-form tags on transactions only. |
| Payee | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | – | ⬜ | ⬜ | – | ⬜ | Free text on transactions in M0. |
| Rule | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | ⬜ | – | ⬜ | |
| Envelope | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | ⬜ | – | ⬜ | |
| Budget period | 🟡 | ✅ | ⬜ | – | – | – | – | – | ⬜ | – | ⬜ | Derived from cycle; past and future navigation on Home. |
| Goal | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | ⬜ | – | ⬜ | |
| Recurring | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | – | ⬜ | – | ⬜ | |
| Occurrence | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | – | – | ⬜ | – | – | |
| Loan | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | – | ⬜ | – | ⬜ | |
| Lending entry | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | – | ⬜ | – | ⬜ | |
| Group / expense | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | – | ⬜ | – | ⬜ | |
| Remittance | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | – | – | ⬜ | – | ⬜ | |
| Circle / contribution | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | ⬜ | – | ⬜ | |
| Attachment | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | ⬜ | – | – | – | ⬜ | |
| Profile | 🟡 | 🟡 | 🟡 | ⬜ | – | – | ⬜ | – | 🟡 | – | ⬜ | Created in onboarding; region switch edits region, currency, cycle; undoable. |
| Space / member | ⬜ | ⬜ | ⬜ | ⬜ | – | – | – | – | ⬜ | – | ⬜ | M2 |
| Invitation | ⬜ | ⬜ | – | ⬜ | – | – | – | – | ⬜ | – | – | M2 |
| Alert / watchlist | ⬜ | ⬜ | ⬜ | ⬜ | – | ⬜ | – | – | ⬜ | – | – | M4 |
| Saved search / report | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | – | ⬜ | – | – | – | ⬜ | FTS4 index exists. |
| Sync link | ⬜ | ⬜ | ⬜ | ⬜ | – | – | – | – | ⬜ | – | – | M5 |
| Device / session | ⬜ | ⬜ | ⬜ | ⬜ | – | – | – | – | ⬜ | – | – | M2 |

## Tests behind the Transaction row
- `core/src/test/.../TransactionLifecycleTest.kt`: create, update every field, delete, restore, purge, undo, history, revert, rebuild from events.
- `data/src/androidTest/.../RoomEventStoreTest.kt`: same lifecycle on encrypted Room, FTS search, rebuild.
- Manual on Moto g84 5G (Android 15), 2026-11-15: onboarding, add income and expense (calculator), deep link `penny://add`, edit, history, revert, swipe delete + undo, trash restore, region switch IN → JP → IN.
