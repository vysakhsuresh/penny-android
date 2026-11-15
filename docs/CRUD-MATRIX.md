# CRUD and lifecycle matrix status

Source of truth for SPEC §10.2. Every PR updates the rows it touches.

Legend: ✅ built (every write is an event, so undo and history come with it) · 🟡 partial (see note) · ⬜ not started · – not applicable

How it is tested: create, update-every-field, delete, restore, purge, undo, history and revert are implemented once in
`EventSourcedRepository` and unit-tested for all entity types (`core/.../TransactionLifecycleTest.kt`,
`SyncConvergenceTest.kt`); domain math has its own suites (budget, rules, splitting, debt, bills, importers, parsers).
Screen-level checks for every row are in `docs/TEST-PLAN.md` for the device run.

| Object | Create | Read | Update all fields | Delete (soft) | Restore | Archive | Reorder | Merge | History | Bulk | Export | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Transaction | ✅ | ✅ | ✅ | ✅ | ✅ | – | – | ✅ dupes | ✅ | ✅ | ✅ | Splits, attachments, place, via, status lock, templates, duplicate merge, bulk category/account/tag/date/delete/merge. |
| Split | ✅ | ✅ | ✅ | ✅ | with parent | – | ✅ | – | ✅ | – | ✅ | Edited inside the transaction; amount or percent with live remainder. |
| Account | ✅ | ✅ | ✅ | ✅ reassign | ✅ | ✅ | ✅ | – | ✅ | – | ✅ | Typed confirmation; move or delete transactions; card cycle; set balance, count cash, reconcile; valuations. |
| Category | ✅ | ✅ | ✅ | ✅ reassign | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Three levels; reset defaults keeps custom ones. |
| Tag | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 | ✅ | ✅ | – | ✅ | Order follows creation; drag reorder not built. |
| Payee | auto ✅ | ✅ | ✅ | ✅ reassign | ✅ | – | – | ✅ | ✅ | – | ✅ | Rename propagates; aliases kept; payee page with trend. |
| Rule | ✅ | ✅ | ✅ | ✅ | ✅ | disable ✅ | ✅ | – | ✅ | – | ✅ | Preview, retro apply, conflicts, learning (draft at 2, on at 3). |
| Envelope | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 | – | ✅ | – | ✅ | Reorder via sort field in code; no drag UI. |
| Budget period | auto | ✅ | limits ✅ | – | – | – | – | – | ✅ | – | ✅ | Allocations and moves are events; past periods read-only with edit anyway; year view. |
| Goal | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 | – | ✅ | – | ✅ | Contribute, withdraw, pause, complete; auto-contribution. |
| Recurring | ✅ | ✅ | ✅ this/future | ✅ | ✅ | pause ✅ | – | – | ✅ | – | ✅ | Detection, subscriptions, calendar. |
| Occurrence | auto | ✅ | ✅ | skip ✅ | unskip ✅ | – | – | – | ✅ | – | – | Pay, skip, move date or amount for one. |
| Loan | ✅ | ✅ | ✅ | ✅ | ✅ | close ✅ | – | – | ✅ | – | ✅ | Amortisation, prepayment what-if, payoff planner. |
| Lending entry | ✅ | ✅ | ✅ | ✅ | ✅ | settle ✅ | – | – | ✅ | – | ✅ | Partial settlement, simple interest, drafted reminder. |
| Group / expense | ✅ | ✅ | ✅ | ✅ | ✅ | archive ✅ | – | – | ✅ | – | ✅ | Five split modes, simplify, settle links, CSV, web view link. |
| Remittance | ✅ | ✅ | ✅ | ✅ | ✅ | – | – | – | ✅ | – | ✅ | True cost vs mid-market; provider comparison. |
| Circle / contribution | ✅ | ✅ | ✅ | ✅ | ✅ | close ✅ | ✅ turn | – | ✅ | – | ✅ | Rounds, who paid, payouts. |
| Attachment | ✅ | ✅ | 🟡 replace | ✅ | ✅ | – | 🟡 | – | – | – | ✅ | Replace = remove and add; order follows adding. |
| Profile | ✅ | ✅ | ✅ | ✅ typed | – | – | 🟡 | – | ✅ | – | ✅ | Decoy profile hidden; erasure removes events. |
| Space / member | ✅ | ✅ | ✅ role | ✅ leave/remove | – | – | – | – | audit ✅ | – | ✅ | Needs the relay; history of the space stream is the audit log. |
| Invitation | ✅ | ✅ | – | revoke ✅ | – | – | – | – | ✅ | – | – | Expires in 7 days on the relay. |
| Alert / watchlist | auto/✅ | ✅ | ✅ | dismiss ✅ | – | mute ✅ | – | – | ✅ | – | – | Quiet hours, batching, back-off. |
| Saved search / report | ✅ | ✅ | 🟡 | ✅ | ✅ | – | 🟡 | – | – | – | ✅ | Rename by deleting and saving again. |
| Sync link | ✅ | ✅ | re-consent ✅ | disconnect ✅ | – | – | – | – | ✅ | – | – | SimpleFIN live; others need provider contracts. |
| Device / session | auto | ✅ | 🟡 rename | sign out ✅ | – | – | – | – | ✅ | – | – | Device name comes from the phone model. |
