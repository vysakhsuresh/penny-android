# Pouch — Product and Engineering Specification

Version 1.0 · 15 November 2026 · Status: ready for implementation
Working name: **Pouch** (see §12; rename is a one-line change in `core/brand.kt`). Do not use "Ledger": it collides with Ledger SAS (hardware wallets) in trademark classes 9 and 36.

---

## 0. How to use this document (read this first, CLI agent)

1. This is the single source of truth. Where the prototype, the master-plan doc and this file disagree, this file wins.
2. Requirement IDs look like `TX-07`. Every requirement has acceptance criteria (AC). A requirement is done only when every AC passes in an automated test or a documented manual test on the device matrix (§10.4).
3. Keywords: **MUST** = required for release; **SHOULD** = required unless a written exception is approved; **MAY** = optional.
4. Read in this order before writing code: §1, §2, §3, §4.2, §5, §6, §7, §9, §10. Then start Milestone M0 (§11).
5. Never ship a module without its CRUD matrix row (§10.2) fully green. The prototype audit (§2) exists because basics were missed; the matrix prevents a repeat.
6. Scope is global. Nothing may be hard-coded to one country, currency, language, pay cycle, bank or payment app. Regional behaviour comes from **regional packs** (§5.9) loaded as data.
7. When a decision is not covered here, choose the option that (a) keeps every feature free, (b) works offline, (c) stores less personal data, (d) is reversible by the user with undo. Write the decision into `DECISIONS.md` with the date.

---

## 1. Product summary

Pouch is a personal and household money tracker for the whole world. It captures every transaction with the least effort available on the user's platform and region, budgets on the user's real pay cycle, models households, friends, lenders and informal savings groups as first-class objects, and shows one number on open: what is safe to spend.

**Positioning:** the benchmark app of the category. Every feature any major tracker offers is present (see the Feature benchmark tab of the master plan), free, and improved. Trust is the brand: no ads, no lending or financial-product cross-sell, no data brokerage, end-to-end encrypted sync, full export at any time.

**Platforms:** Android and iOS (phones and tablets), web app, watch tiles. Desktop via web. Offline-first everywhere.

**Non-negotiables**
- NN-1 Every feature is free. The only paid tier (Pouch Plus) is cost pass-through: bank auto-sync fees, storage beyond 1 GB, priority support.
- NN-2 Everything works offline; sync is additive.
- NN-3 Every write is undoable for at least 5 seconds and recoverable from history for 30 days.
- NN-4 No ads, no loans, no credit scores, no savings accounts, no cashback, no data sale. Stated in-app and in store listings.
- NN-5 Global by data, not by code: locale, currency, cycle, rails and language are configuration.
- NN-6 Accessibility WCAG 2.2 AA on all platforms.
- NN-7 Nothing is hidden behind a paywall surprise; Plus screens are visible and explained.

---

## 2. Lessons from the prototype audit

The interactive prototype (Nov 2026) was audited screen by screen against fundamentals. It was a design probe, not a product, but each gap below is a requirement that must exist in the real app. IDs map to §4.

| # | Gap found in prototype | Severity | Requirement |
| --- | --- | --- | --- |
| 1 | Transactions could not be edited or deleted (fixed later, but should have been day one) | Critical | TX-02, TX-03, TX-05 |
| 2 | Only expenses; no income, transfers, refunds or balance adjustments | Critical | TX-01 |
| 3 | No user accounts, profiles, households or multi-user | Critical | US-01…US-14 |
| 4 | No persistence; reload lost everything | Critical | SY-01 |
| 5 | Envelopes, accounts, goals, recurring items, lending entries had add but no edit, archive or delete | Critical | CRUD matrix §10.2 |
| 6 | Category management absent (create, rename, merge, archive, reassign) | High | CAT-01…CAT-08 |
| 7 | No date or time picker; period boundaries ignored timezone; backdating did not move spend to the right period | High | TX-06, BU-03, LO-06 |
| 8 | Search and filters were decorative | High | SR-01…SR-06 |
| 9 | Recurring "Paid" removed the item instead of generating the next occurrence; no skip, no edit this/future | High | RC-01…RC-08 |
| 10 | Transfers double counted or were hacked as "pass-through" accounts | High | TX-01, AC-07 |
| 11 | Credit cards had no statement cycle, due date or minimum due | High | AC-05 |
| 12 | Validation absent: zero and negative amounts, decimal rules by currency, maximum amounts | High | TX-08, LO-03 |
| 13 | Undo existed for some actions only; no history view | High | TX-05, HS-01 |
| 14 | Cash reconcile posted the difference to an arbitrary category | Medium | AC-08 |
| 15 | Goals had no contribute, withdraw or complete actions | Medium | GO-01…GO-06 |
| 16 | Lending had no partial settlement, interest, or history | Medium | PE-01…PE-07 |
| 17 | Household members could not be invited, removed or given roles | Critical | US-07…US-12 |
| 18 | Review queue had no "edit before accept" | Medium | CP-04 |
| 19 | Notifications were simulated; no OS permission flow, quiet hours, or per-envelope settings | Medium | AL-01…AL-09, NT-01 |
| 20 | No empty, loading or error states | Medium | UX-01 |
| 21 | Hardcoded strings; no RTL; decimal separator assumed "." | High | LO-01…LO-08 |
| 22 | Sheets did not trap focus; screen-reader announcements missing | High | AX-01…AX-06 |
| 23 | Hash routing lost detail context (envelope id) | Low | UX-07 |
| 24 | No app lock, encryption, sessions or device list | Critical | SE-01…SE-09 |
| 25 | Backup, restore, export and delete-account were buttons only | Critical | SY-05…SY-09 |
| 26 | Insights had no date range, no comparison, no drill-down | Medium | IN-01…IN-10 |
| 27 | Purchase flow had no restore, receipt or regional pricing logic | Medium | MO-01…MO-05 |
| 28 | No settings for first day of week, fiscal year, default account, decimal places, time zone | Medium | ST-01 |
| 29 | Only two regional packs (India, Philippines) | Critical | LO-09, §5.9 |
| 30 | Deleting a category, account or member with history had no reassignment flow | High | CAT-06, AC-06, US-11 |
| 31 | Payees/merchants were free text with no entity, no merge, no logo | Medium | PY-01…PY-04 |
| 32 | Amount entry had no calculator (e.g. 120+80) | Low | TX-09 |
| 33 | No duplicate detection on manual entry | Medium | TX-10 |
| 34 | No attachments beyond a chip; no camera, gallery, file picker | Medium | TX-11 |
| 35 | Tablet and landscape layouts absent | Medium | UX-08 |
| 36 | No pending vs cleared vs reconciled status | Medium | TX-12 |
| 37 | Budget "available to assign" missing; income not part of budgeting | High | BU-05 |
| 38 | Period could not be viewed historically or in the future | High | BU-08 |
| 39 | No audit log in shared spaces | High | US-13 |
| 40 | No deep links or share-sheet intake | Medium | CP-05, UX-09 |

Rule going forward: before any screen is called done, walk §10.2 for each object it shows.

---

## 3. Users, profiles, households and sharing

### 3.1 What the leaders do (researched Nov 2026)
- YNAB: one subscription, one shared plan, up to six people each with their own login, real-time co-editing.
- Monarch Money: partner added free with their own login; joint reports; "Monarch for Professionals" gives advisors a client portal.
- Wallet by BudgetBakers: owner invites members by registered email; per-member access rights per account, including "No access".
- Money Lover: share a wallet with another user by email.
- Spendee: shared wallets with members; budgets inside shared wallets.
- Veryfi, iExpenses, Money Pro, MoneyBook Pro: separate profiles or "books" (personal, business, trip), usually three free; each with its own currency.
- Quicken Simplifi: separate profiles under one account, accounts linked per profile.
- Copilot: no sharing; multiple profiles is a top feature request.
- Splitwise: groups of friends, simplified debts, non-users via email.
- Firefly III (open source): multi-user via separate logins; Actual Budget: multiple budget files per server.

Nobody combines all four layers (login, books, shared spaces, friend groups) with per-object permissions. Pouch does.

### 3.2 Pouch model

```
Account (login)
 ├── Profile "Personal"      (a book: own base currency, categories, budgets, accounts)
 ├── Profile "Business"      (separate book, separate reports; optional)
 ├── Profile "Kids: Aarav"   (supervised book; allowance)
 └── Membership in Spaces    (shared books owned by any account)
Space "Our home"             (owner + members with roles; shared accounts and envelopes)
Group "Goa trip"             (split-expense group; members may be non-users)
```

Requirements:

- US-01 An Account MUST support email+passcode, phone OTP, passkeys, Sign in with Apple/Google, and a local-only mode with no account (data stays on device, upgradeable later without loss).
- US-02 An Account MUST own one or more Profiles. Free limit: unlimited. Each Profile has its own base currency, categories, payees, budgets, accounts, rules, settings and reports. Switching profiles is one tap from the avatar.
- US-03 A Profile MAY be marked **Supervised** (child or dependant). A supervisor sees and edits it; the supervised user may be given a device login with limited scope (add transactions, view own envelopes).
- US-04 A Profile MAY be a **Business book** with features toggled on: tax tags, client/project tags, invoice attachment, mileage, GST/VAT fields, export in accountant formats.
- US-05 A **Space** is a shared book. An Account creates it and becomes Owner. Members join by invite link, email, phone or QR. Free limit: 10 members per space, unlimited spaces.
- US-06 Roles: Owner (all; transfer ownership; delete space), Admin (manage members, settings), Editor (add/edit/delete any transaction, budgets), Contributor (add transactions; edit/delete own), Viewer (read). Roles are per Space and overridable per Account or Envelope (e.g., Viewer on "Rent", Editor on "Groceries", No access on "Gifts").
- US-07 Any transaction in a Space MAY be flagged Private by its creator: amount counts in totals; merchant, note and attachments are hidden from others.
- US-08 A member MAY leave a Space; an Owner MAY remove a member. Their past transactions remain with an "ex-member" label; their private data is scrubbed on request.
- US-09 Personal profile and Space can be linked: a transaction entered in Personal MAY be mirrored to a Space envelope (one source of truth, two views) with a "shared" toggle in the add sheet.
- US-10 **Groups** (friend splitting): any member creates a group; non-users participate by email/phone and get a web-only read/settle view; balances simplify; settle via regional payment deep links (UPI, GCash, PayNow, Pix, PayPal, Venmo, SEPA request) or mark as paid.
- US-11 Deleting an Account requires reassignment or export of owned Spaces; dependants' profiles transfer to another supervisor or are exported and deleted.
- US-12 Invitations expire in 7 days, are revocable, and show what will be shared before acceptance.
- US-13 Every Space keeps an audit log (who did what, when, old and new values), visible to Admin+.
- US-14 **Advisor links**: a time-limited, revocable read-only web link to a report set for an accountant, adviser or elder; no login needed; watermarked; never includes attachments unless chosen.
- US-15 Multiple Accounts MAY be signed in on one device (work and personal); switching is in the avatar menu; each keeps its own encryption keys.

---

## 4. Functional requirements

Each module lists requirements and acceptance criteria. "Object" rows in §10.2 define the minimum CRUD per object.

### 4.1 Onboarding and setup (OB)
- OB-01 Setup MUST complete in ≤ 60 seconds with three steps: region and language; pay cycle; first account (cash) with balance. Every step skippable with sane defaults from the device locale.
- OB-02 Region sets a regional pack (§5.9); user may change region, currency, cycle later without data loss.
- OB-03 Offer "Import from another app" on step 3 with the migration importers (CP-09).
- OB-04 Offer "Join a household" via invite link.
- AC: a new user on a fresh device can add a transaction within 90 s in usability tests; all defaults are reversible in Settings.

### 4.2 Transactions (TX)
- TX-01 Types: expense, income, transfer (between own accounts, never double counted), refund (negative expense linked to original), adjustment (balance correction, categorised "Adjustment" and excluded from spend reports), opening balance. Transfers between accounts of different currencies record both amounts and the implied rate.
- TX-02 Create, read, update, delete for every field: amount, currency, account, to-account (transfer), category, subcategory, payee, date, time, note, tags, attachments, location (optional), status, splits, linked recurring, linked group/lending/remittance, private flag, exclude-from-budget flag, exclude-from-reports flag.
- TX-03 Delete is soft for 30 days (Trash view, restore, purge). Every delete shows a 5 s undo toast; no confirmation dialogs.
- TX-04 Bulk actions: multi-select → change category, account, tags, date; delete; merge duplicates; export selection.
- TX-05 Versioned: every edit stores a version with actor, timestamp and diff; "History" on the transaction shows versions and allows revert.
- TX-06 Date and time: full calendar picker, quick chips (Today, Yesterday, custom), time optional, stored in UTC with the user's timezone; budget period assignment uses the profile's timezone and period rules; moving the date moves period attribution.
- TX-07 Splits: by amount, percent, or receipt line items; each split has own category, tags, note and may be assigned to people in a Group; sum must equal total (validated live).
- TX-08 Validation: amount > 0 (sign comes from type), minor-unit precision per currency (0, 2 or 3), max 15 digits, future dates allowed (flagged "scheduled"), account required, category optional (defaults to "Uncategorised", surfaced in review).
- TX-09 Amount field is a calculator: `+ - × ÷ %`, parentheses, and locale decimal separator; shows the evaluated result before save.
- TX-10 Duplicate detection: same account, amount within ±0, date within 24 h, similar payee → warn with "Keep both / Merge".
- TX-11 Attachments: camera, gallery, files, share sheet; images compressed on device; PDFs allowed; OCR offered; 1 GB free per account.
- TX-12 Status: pending, cleared, reconciled. Bank sync sets pending/cleared; reconcile locks reconciled items (edit requires un-reconcile with warning).
- TX-13 Swipe gestures on rows: right = change category sheet; left = delete with undo; long-press = multi-select.
- TX-14 Quick add sheet: amount keypad with usual-amount chips, six smart category chips (time-of-day and recency weighted), account pill, date chip, mic, camera, "More" expands to full form. Target: 3 taps and ≤ 3 s for a common spend.
- TX-15 Payee memory: picking a payee pre-fills last category, account and typical amount.
- TX-16 Templates and favourites: save any transaction as a template; widgets can fire a template in one tap.
- AC: 100% of fields editable after creation; edit, delete, restore, bulk edit covered by tests; period totals recompute within 100 ms on 50k transactions.

### 4.3 Accounts (AC)
- AC-01 Types: cash, bank (current/savings), e-wallet, credit card, prepaid/gift card, loan (owed), receivable, investment (manual or synced), asset (property, vehicle, gold by weight), liability, other. Each type has its own icon and rules.
- AC-02 CRUD, archive (hidden, history kept), reorder, group (by type or custom), include/exclude from net worth and from budgets.
- AC-03 Opening balance and date; balance history derived from the event log; manual "set balance today" creates an adjustment.
- AC-04 Multi-currency accounts: each account has one currency; the profile has a base currency; conversions use the rate on the transaction date (daily rates cached offline; manual override allowed).
- AC-05 Credit cards: credit limit, statement day, due day, minimum due %, interest APR; app shows statement balance, unbilled, due in N days, utilisation; a payment from a bank account is a transfer; interest and fees are expenses.
- AC-06 Deleting an account with transactions requires choosing: move transactions to another account, or delete them (soft).
- AC-07 Pass-through wallets (e.g., a UPI app that debits a bank) MAY be modelled as a linked alias so the transaction posts to the bank account with the wallet as "via"; no double counting.
- AC-08 Cash reconcile: "Count my cash" → difference posts as an adjustment to the "Unrecorded cash" category (reportable), with an option to split it into categories later.
- AC-09 Sync status per account: provider, last successful fetch, consent expiry, re-consent button, "manual" badge.
- AC: net worth equals the sum of included account balances to the minor unit at any date; archived accounts never appear in pickers but remain in reports when their date range includes activity.

### 4.4 Categories and payees (CAT, PY)
- CAT-01 Three-level hierarchy; expense and income trees; per-profile; icons and colours; sort order.
- CAT-02 Defaults from the regional pack (localised names) plus a universal set; user can reset to defaults without losing custom ones.
- CAT-03 CRUD, archive, reorder, move within tree, merge (A into B, re-tags all transactions and rules).
- CAT-04 Budgetable flag; "excluded from spend" flag (e.g., Transfers, Adjustments, Reimbursable).
- CAT-05 Per-category settings: default account, default tags, alert thresholds (AL-05), rollover default.
- CAT-06 Deleting a category with transactions requires reassignment to another category.
- CAT-07 Tags: free-form, colour, merge, archive; smart tags (Reimbursable, Tax-deductible, Trip, Gift, Business) with reports.
- CAT-08 Category rules preview: before saving a rule, show how many past transactions it would change and offer to apply retroactively.
- PY-01 Payee is an entity: display name, aliases (raw strings seen), logo (community map or user image), default category, website, notes.
- PY-02 Merge payees; rename propagates; raw strings kept for future matching.
- PY-03 Community merchant map (opt-in, anonymised): maps raw descriptors to clean names and categories per region; user corrections feed it; never uploads amounts.
- PY-04 Payee page: total spent, frequency, average, trend, all transactions.

### 4.5 Budgets (BU)
- BU-01 Budget period = the profile's pay cycle: monthly (any start day), semi-monthly (any two days, e.g., 15 and last day), weekly, fortnightly (anchor date), 4-weekly, quarterly, yearly, or "when money lands" (period starts when an income ≥ threshold posts). Per-envelope override of period is allowed (e.g., yearly insurance inside a monthly budget).
- BU-02 Envelopes map to a category, a category group, or a custom set of categories/tags; limits per period; unlimited envelopes.
- BU-03 Period boundaries are computed in the profile timezone; a transaction belongs to the period containing its date; changing cycle re-attributes history deterministically and shows a before/after summary.
- BU-04 Rollover modes: none; carry positive only; carry positive and negative; carry up to a cap.
- BU-05 "Available to assign": income for the period minus assigned; zero-based mode shows it on top; non-zero-based users can hide it.
- BU-06 Targets: fixed per period, "save by date", "refill up to", "spend at most"; auto-assign on payday from targets or last period; "underfunded first".
- BU-07 Safe-to-spend = income (actual plus expected scheduled) − committed scheduled bills − assigned savings − spent, per period and per day; pace marker; amber at pace overrun, never red.
- BU-08 Period navigation: past periods read-only with "edit anyway"; future periods editable for planning; year view with 12 columns.
- BU-09 Templates: regional (salaried, gig, student, household, OFW family, small business), copy last period, start from zero.
- BU-10 Move money between envelopes (cover from) with history; alert integration (AL-03).
- BU-11 Festival and seasonal pots: a goal-type envelope with auto-contributions sized from last year's spend window; regional pack supplies the calendar (Diwali, Christmas, Eid, Lunar New Year, Thanksgiving, back-to-school, Songkran, Tet, Nowruz…).
- BU-12 Income smoothing: optional buffer envelope that pays a fixed "salary" into available-to-assign on a schedule from irregular income.
- AC: period math has property-based tests for every cycle type across DST changes and month ends (28/29/30/31).

### 4.6 Goals (GO)
- GO-01 Goal: target amount, optional date, linked account(s), auto-contribution rule in the user's cycle, icon, notes.
- GO-02 Actions: contribute, withdraw, pause, complete, archive, delete (returns balance to available).
- GO-03 Projection: on track / behind with the per-period amount needed.
- GO-04 Shared goals in Spaces with per-member contributions shown.
- GO-05 Goal history and celebration state (no confetti by default; a calm card).
- GO-06 Emergency fund template: N months of average essential spend, computed from data.

### 4.7 Recurring and bills (RC)
- RC-01 Recurring template: payee, amount (fixed, variable with estimate, or range), account, category, schedule (RRULE: daily/weekly/monthly/yearly, nth weekday, last day, every N, end date or count), auto-post or remind, reminder lead time.
- RC-02 Generates upcoming occurrences; each occurrence can be marked paid (creates the transaction), skipped, edited for this occurrence only, or edited for this and future.
- RC-03 Detection: rules engine proposes recurring items from repeating payee+amount; user confirms.
- RC-04 Subscriptions view: active, price changes, last used (if detectable), total yearly cost, cancellation guide per service per region, drafted cancellation message. No success fee ever.
- RC-05 EMIs and instalments: principal, rate, tenure, start date → amortisation schedule, interest paid to date, remaining, prepayment what-if, penalty fields.
- RC-06 Bill calendar: month view; each bill shows "covered by safe-to-spend" or shortfall.
- RC-07 Variable bills learn: estimated amount = median of last 6 actuals.
- RC-08 Recurring items can be shared in Spaces with an assigned payer.

### 4.8 Debt and loans (DB)
- DB-01 Debts: cards, EMIs, personal loans, pay-later, informal loans; all appear in one payoff planner.
- DB-02 Strategies: avalanche, snowball, custom order, fixed extra per period; projection chart and date debt-free.
- DB-03 Loans given (receivables) appear in net worth and in People (PE).

### 4.9 People: lending and splitting (PE)
- PE-01 Lending ledger per counterparty: gave/received entries, running balance, optional due date, optional interest (simple), notes, attachments, partial settlements.
- PE-02 Neutral wording ("to receive", "to give"); no overdue badges on Home; reminders are drafted messages the user sends themselves (copy or share sheet).
- PE-03 Groups (US-10): expenses with payer and shares (equal, exact, percent, shares, by item); recurring group expenses; simplify debts; settle-up with payment deep links; currency per expense with group base currency; export.
- PE-04 Settling a lending or group balance posts matching transactions in the user's own ledger (category "Loan given/returned" excluded from spend).
- PE-05 Counterparties link to contacts (permission-gated) or are free text; non-users invited by link.
- PE-06 Person page: all shared history across lending and groups.
- PE-07 Dispute flag on a group expense notifies the creator; no money moves inside Pouch.

### 4.10 Remittances and circles (RM, CI)
- RM-01 Remittance object: sender, receiver (member or external), sent amount and currency, fee, FX rate, received amount and currency, provider, purpose, date, status (sent, received, confirmed). Both sides see one record in a Space.
- RM-02 True cost = fee% + rate spread vs mid-market rate of the day; provider comparison over time; no provider referrals.
- RM-03 Received amount can seed the receiver's period income and "when money lands" period start.
- CI-01 Circle (rotating savings and credit association) object with regional names from the pack: chit (India), paluwagan (Philippines), tanda (Mexico), susu (West Africa/Caribbean), hui (China/Vietnam), ekub (Ethiopia), stokvel (South Africa), kameti (Pakistan), arisan (Indonesia), cundina, pandero.
- CI-02 Fields: members, contribution, frequency, start, payout order (fixed, lottery, bid), pot, fees/commission, your turn; each contribution posts as a transaction; payout turn is a goal that fills itself.
- CI-03 Reminders before each contribution; ledger of who has paid, visible to all members of the circle's Space or Group.

### 4.11 Capture (CP)
- CP-01 Manual quick add (TX-14) everywhere, including widgets, watch, keyboard shortcuts on web.
- CP-02 Android notification listener: per-app parsers for payment and bank apps supplied by regional packs (e.g., Google Pay, PhonePe, Paytm, GCash, Maya, Venmo, Cash App, Revolut, Monzo, Nubank, PayNow, Pix, M-Pesa, bKash); on-device only; opt-in with plain explanation; off switch; never requests SMS permission.
- CP-03 iOS: share sheet from any app, Shortcuts actions, Wallet pass import, email forwarding (CP-06).
- CP-04 Review queue: captured items show source, confidence, suggested category; accept, edit-then-accept, ignore, "always ignore this source/payee"; auto-post per source after N consistent accepts (default 10) and user opt-in.
- CP-05 Share-sheet intake: text (parsed), images (OCR), PDFs, CSV.
- CP-06 Email forwarding: per-account forwarding address; parsers for major e-commerce and ride/food platforms per region; raw email deleted after parsing.
- CP-07 OCR: on-device model; extracts total, tax, date, merchant, line items; multi-currency receipts; free, unlimited.
- CP-08 Voice: on-device speech where available; grammar handles amount, payee, category, account, date, split with people, in the user's languages including mixed speech.
- CP-09 Imports: CSV with column mapper and saved bank presets, OFX, QIF, QFX, MT940, CAMT.053, PDF statements (tabular extraction), and migration importers for YNAB, Monarch, Mint archive, Money Manager, Spendee, Wallet, Splitwise, Bluecoins, Toshl, Goodbudget, EveryDollar, PocketGuard, Actual, Firefly III. Importer preview with duplicate handling.
- CP-10 Bank sync (Plus): regional providers behind one connector interface: Plaid (US/CA/UK/EU), MX/Finicity (US), TrueLayer/Yapily/GoCardless/Tink/Salt Edge (UK/EU), Basiq (AU CDR), Akahu (NZ), Account Aggregator via a TSP (IN), BSP Open Finance partners (PH), Belvo/Pluggy (LATAM, Open Finance Brazil), Mono/Okra/Stitch (Africa), SGFinDex (SG), Finverse/Brankas (SEA), SimpleFIN (self-serve). Consent screens, expiry, re-consent, per-account toggle, delete-on-disconnect.
- CP-11 Dedupe across sources (notification + bank + manual): amount, time window, account, payee similarity; merged items keep all sources in history.
- CP-12 Location (optional, on-device): suggest payee from nearby previous spends.

### 4.12 Rules engine (RU)
- RU-01 Rule = conditions (payee contains/regex, amount range, account, source, day/time, currency, notification app) + actions (category, subcategory, tags, payee rename, account, mark transfer, split template, exclude flags, private flag, auto-post).
- RU-02 Rules ordered; first match wins unless "continue"; dry-run preview; apply retroactively option; import/export rules JSON.
- RU-03 Learning: a user correction creates a draft rule after 2 identical corrections and promotes it after 3; user can see, edit or disable learned rules.
- RU-04 Community suggestions (opt-in) for well-known payees per region; never override a user rule.
- RU-05 Conflict surfacing: when two rules would both match, show it in Settings → Rules → Conflicts.

### 4.13 Alerts and nudges (AL)
- AL-01 Thresholds per envelope: 50% (digest only), 80% near par, 100% at par, then daily when over; all configurable per envelope (off / near / near+over).
- AL-02 Pace: after day 3 of the period, if spend share exceeds elapsed share by ≥ 15 points, Home turns amber with the projected run-out date; one notification per day max.
- AL-03 Over par offers "Cover from" the envelope with the most unused money; accepting moves limit and logs it.
- AL-04 Safe-to-spend low (< 3 days of average daily spend); bill due in 2 days with coverage status; large transaction (> 3× category average); new recurring detected; price increase on a subscription; unusual merchant for this profile; consent expiring; sync broken.
- AL-05 Watchlists on payee, tag or category with own thresholds.
- AL-06 Delivery: in-app banner, Home alerts card, OS notification, widget badge, weekly digest; quiet hours default 21:00–09:00 local; no sound by default; batching of multiple crossings; back-off after two ignored alerts for the same envelope in a period.
- AL-07 Shared spaces: alert goes to Admins and the member who made the spend; others silent unless subscribed.
- AL-08 Never red; never a blocking dialog; every alert states the number, days left and one action.
- AL-09 Forecast alert: on day 5, "at this pace you end the period X short" with the envelopes driving it.

### 4.14 Search and filters (SR)
- SR-01 Full-text search over payee, note, tags, category, amount (exact or range), account, attachments' OCR text; instant results; offline.
- SR-02 Filter chips: date range (presets and custom), accounts, categories, tags, people, status, source, amount range, currency, has attachment, private, uncategorised.
- SR-03 Saved searches ("Reimbursable this quarter") pinned to Transactions and usable in widgets and reports.
- SR-04 Natural-language search ("coffee last month over 200", "jeep rides last kinsenas") parsed on device into filters; shows the parsed filter for correction.
- SR-05 Sort by date, amount, payee, category, created time.
- SR-06 Search results support bulk actions (TX-04).

### 4.15 Insights and reports (IN)
- IN-01 Period summary: spent vs budget, income vs spend, top categories, top payees, change vs previous period and same period last year.
- IN-02 Charts: category donut with legend, trend bars by period, cash-flow waterfall, Sankey (income → envelopes → payees), net-worth line, balance history per account, calendar heatmap of spend. Bars from zero; one accent; colours follow entities consistently.
- IN-03 Date range picker for any report; compare mode; drill-down from any mark to the transactions.
- IN-04 Custom reports: choose measures, dimensions, filters, chart type; save; schedule to digest; export CSV/PDF/PNG.
- IN-05 Net worth: all accounts with include flags; assets with manual valuation history; gold by weight and daily price (regional pack); investment holdings when synced.
- IN-06 Forecast: end-of-period and 90-day balance projection from scheduled items and run-rate; scenario sliders ("if I cut eating out 20%").
- IN-07 Household views: by member, shared vs personal, who paid what; group balances.
- IN-08 Coach (on-device): answers questions over the user's own data, always listing the transactions behind the answer; tone setting (gentle, direct, roast); no server call for personal data; no product suggestions.
- IN-09 Weekly and monthly digest in the user's language; shareable as image to the household.
- IN-10 Tax and business reports when Business book is on: by tax tag, by client, VAT/GST summary, mileage.

### 4.16 Space features (SP)
- SP-01 Space Home: shared safe-to-spend, member chips, shared envelopes, recent shared activity, open balances between members.
- SP-02 Per-envelope sharing and per-account visibility (US-06).
- SP-03 Settlements between members ("you covered groceries, I owe you") derived from shared accounts and splits.
- SP-04 Allowance: scheduled transfer to a supervised profile; supervised view shows balance and own spends only.
- SP-05 Activity feed with filters and the audit log (US-13).

### 4.17 Settings (ST)
- ST-01 Profile: name, avatar, base currency, timezone, pay cycle, first day of week, fiscal year start, number format (including lakh/crore), date format, default account, default transaction type, decimal places override, rounding display.
- ST-02 Appearance: theme (system/light/dark/true black/high contrast), accent, text size, dense mode, haptics, reduce motion.
- ST-03 Capture: each source with explanation and switch; notification app list; auto-post per source; OCR language packs; voice languages; email forwarding address.
- ST-04 Alerts: global and per-envelope; quiet hours; digest day/time; channels.
- ST-05 Categories, payees, tags, rules, templates management screens.
- ST-06 Accounts and sync: providers, consents, re-consent, disconnect and delete data.
- ST-07 Spaces, members, invites, roles; groups; advisor links.
- ST-08 Security: app lock (biometric/PIN), auto-lock timer, duress PIN (opens decoy book), screenshot blocking toggle, sessions and devices with remote sign-out, recovery kit.
- ST-09 Data: export (CSV per table, JSON full, encrypted archive), import, backup to user's cloud folder, restore, trash, delete profile, delete account.
- ST-10 Language: app language independent of region; RTL mirrors layout.
- ST-11 Pouch Plus: status, manage, restore purchase, invoices.
- ST-12 About: trust page (what we never do), privacy policy, open-source licences, changelog, feedback with optional diagnostics.

### 4.18 Security and privacy (SE)
- SE-01 Local database encrypted at rest (SQLCipher or platform-equivalent) with a key in the secure enclave/keystore.
- SE-02 Sync is end-to-end encrypted (libsodium): per-profile data key wrapped by the user's master key derived from passkey/passphrase; per-space keys wrapped for each member; server stores ciphertext and opaque metadata only.
- SE-03 Recovery kit: 12-word phrase or exported key file, shown once; optional trusted-contact recovery (Shamir 2-of-3) for households.
- SE-04 App lock with biometrics and PIN; auto-lock on background after N seconds; amounts hidden in app switcher.
- SE-05 Duress PIN opens a decoy profile with plausible sample data; real data stays locked.
- SE-06 Sessions: device list, last active, remote sign-out, new-device sign-in notification.
- SE-07 Attachments encrypted client-side before upload; thumbnails generated on device.
- SE-08 Bank connector tokens never touch the client; connector service holds them under the regional regulation with minimum retention; disconnect deletes.
- SE-09 Data deletion: one tap deletes everything from device and relay within 24 h; confirmation by typing the profile name; export offered first.
- SE-10 Analytics: event counts only, no amounts, payees or notes; opt-out; no third-party ad SDKs.
- SE-11 Compliance baseline: GDPR/UK GDPR, CCPA/CPRA, LGPD, India DPDP 2023, Philippines DPA 2012, PDPA (SG/TH/MY), APP (AU), PIPL readiness; data residency choice (EU, US, India, Singapore) at account creation.

### 4.19 Sync, offline, backup (SY)
- SY-01 Local SQLite append-only event log is the source of truth; views are materialised tables rebuilt from events; all screens work with no network.
- SY-02 Sync pushes encrypted event batches; pulls others' events; CRDT-style last-writer-wins per field with vector clocks; conflicts keep both versions in history and never lose data.
- SY-03 Multi-device: unlimited devices; first sync of a large profile streams by period, newest first.
- SY-04 Background sync on app open, on change (debounced), and periodically; manual pull-to-sync; sync status indicator with last time and pending count.
- SY-05 Backup: automatic encrypted backup to the user's own cloud folder (Google Drive, iCloud Drive, OneDrive, Dropbox, local folder) on a schedule; keep N versions.
- SY-06 Restore from backup or from relay to a new device; integrity check; dry-run shows what will be restored.
- SY-07 Export: CSV per table, full JSON with schema version, encrypted archive; no paywall ever.
- SY-08 Import of own export restores everything including attachments, rules and settings.
- SY-09 Relay deletion on account deletion within 24 h; backups in the user's cloud are theirs to delete.

### 4.20 OS integration (NT, WG)
- NT-01 OS notification permission requested only when the user enables an alert; explain before the system prompt; never nag.
- WG-01 Widgets: safe-to-spend, quick-add chips, envelope progress, upcoming bills, group balances; sizes small/medium/large; lock-screen widgets; live activities for a trip group.
- WG-02 Watch (Wear OS, watchOS): quick add with templates and voice; safe-to-spend complication.
- WG-03 Shortcuts, Tasker, Android Quick Settings tile; deep links `pouch://add?amount=…&category=…`.
- WG-04 Keyboard shortcuts on web; command palette.

### 4.21 Monetization (MO)
- MO-01 Pouch Plus: bank auto-sync, storage beyond 1 GB, priority support, early access, supporter badge; one Plus covers a Space (up to 10 members).
- MO-02 Regional price points stored as data; monthly, yearly (~40% off), lifetime in launch and festival windows; store billing (Play, App Store) and web billing (Stripe/Paddle) with cross-platform entitlement.
- MO-03 Restore purchases; family sharing where the store supports it; grace periods; downgrade keeps all data and features except sync (accounts turn manual, nothing deleted).
- MO-04 Plus sheet shows what it funds in plain words; no countdown timers, no dark patterns, cancel in two taps.
- MO-05 Voluntary tip jar MAY exist; never gates features.

### 4.22 History and trash (HS)
- HS-01 Global History screen: every change in the profile/space, filterable by object, actor, date; revert any change where safe (creates a new compensating event).
- HS-02 Trash: deleted transactions, accounts, categories, goals, recurring items, members; restore or purge; auto-purge at 30 days.

---

## 5. Global localisation

- LO-01 Languages at launch (minimum): English, Spanish, Portuguese (BR), French, German, Italian, Dutch, Polish, Turkish, Russian, Ukrainian, Arabic (RTL), Hebrew (RTL), Hindi, Bengali, Tamil, Telugu, Marathi, Malayalam, Urdu (RTL), Filipino, Cebuano, Indonesian, Malay, Vietnamese, Thai, Chinese (Simplified, Traditional), Japanese, Korean, Swahili. Strings externalised; ICU MessageFormat for plurals and gender; pseudo-localisation in CI.
- LO-02 All ISO 4217 currencies with correct minor units (JPY 0, KWD 3, etc.); currency symbol placement and spacing by locale; code vs symbol toggle.
- LO-03 Number input honours the locale decimal and grouping separators; keypad shows the right separator; paste normalises.
- LO-04 Number display: Indian grouping (12,34,567) where the locale uses it, with a global override; compact forms (1.2K, 1.2 lakh, 1.2M) per locale in charts.
- LO-05 Dates: locale formats, 12/24 h, first day of week, calendars (Gregorian primary; Hijri, Buddhist, Hebrew display optional), relative dates localised.
- LO-06 Timezones: stored per profile; travel does not re-attribute history; a "travel mode" switches display currency and suggests the local currency in quick add.
- LO-07 RTL: full mirroring, including charts' axes and swipe directions.
- LO-08 Voice and OCR language packs downloadable per language; mixed-language speech handled for common pairs (Hinglish, Taglish, Spanglish, Arabizi).
- LO-09 Regional packs (data files, no code): default categories and names; pay-cycle default; payment and bank apps for notification parsing; aggregator provider; holidays and festival windows; informal-finance names (circles); tax year; number grouping; typical amounts for chips; cancellation guides; settlement deep-link schemes; store price points; privacy-law text. Launch packs: US, CA, UK, IE, EU (per country), AU, NZ, IN, PH, ID, MY, SG, TH, VN, PK, BD, LK, NP, AE, SA, EG, NG, KE, ZA, GH, BR, MX, AR, CO, CL, PE, JP, KR, CN (Traditional/HK/TW), TR, RU, UA. A generic pack applies elsewhere.
- LO-10 Pay cycles by default per pack: weekly/biweekly (US, CA), fortnightly (AU, NZ, UK partly), monthly (EU, IN, most), semi-monthly (PH, MX quincena, some LATAM), "when money lands" suggested for remittance-receiving and gig profiles.
- LO-11 Fiscal years per pack: UK 6 Apr, IN 1 Apr, AU 1 Jul, NZ 1 Apr, JP 1 Apr, others 1 Jan; business books can override.

---

## 6. Data model

Entities (fields abbreviated; all carry `id`, `profile_id` or `space_id`, `created_at`, `updated_at`, `deleted_at`, `version`, `actor_id`):

- `Account(login)`: auth identities, residency region, devices, plus_entitlement.
- `Profile`: name, type (personal, business, supervised), base_currency, timezone, cycle_rule, settings_json, supervisor_account_id.
- `Space`: name, owner_account_id, base_currency, settings; `SpaceMember`: role, per_object_overrides.
- `Group`: name, base_currency; `GroupMember` (account or external contact).
- `FinancialAccount`: type, currency, opening_balance, opening_date, archived, include_net_worth, include_budget, card_fields, sync_link_id, sort.
- `Transaction`: type, amount_minor, currency, account_id, to_account_id, to_amount_minor, category_id, payee_id, date, time, tz, note, status, flags, recurring_id, group_expense_id, lending_id, remittance_id, circle_id, location, source, confidence, external_ids[].
- `Split`: transaction_id, amount_minor, category_id, tags, note, person_id.
- `Attachment`: transaction_id, blob_ref, mime, ocr_text, size.
- `Category`: parent_id, kind (expense, income), name, icon, colour, budgetable, excluded, sort, archived.
- `Tag`, `Payee` (aliases[], logo, default_category_id), `Rule` (conditions_json, actions_json, order, learned, enabled).
- `BudgetPeriod`: cycle_rule, start, end; `Envelope`: name, scope_json, limit_minor, rollover_mode, cap, period_override; `EnvelopeAllocation` (period_id, envelope_id, assigned_minor, carried_minor).
- `Goal`: target_minor, due, linked_accounts, rule_json, status.
- `Recurring`: template_json, rrule, next_due, auto_post, lead_days; `Occurrence`: status (upcoming, paid, skipped), transaction_id.
- `Loan`: principal, apr, tenure, start, schedule_json.
- `Lending`: counterparty_id, direction, entries[]; `GroupExpense`: payer, shares[]; `Settlement`.
- `Remittance`, `Circle`, `CircleContribution`.
- `Alert`: kind, object_ref, fired_at, delivered_via, state. `Watchlist`.
- `Event` (append-only): seq, entity, entity_id, op, patch_json, actor, device, ts, vclock, ciphertext_ref.
- `SyncLink`: provider, institution, consent_expiry, accounts[], last_fetch, status.
- `AuditEntry` (spaces), `Invitation`, `AdvisorLink`.

Invariants (enforced in the domain layer and tested):
1. Sum of splits equals transaction amount.
2. A transfer creates exactly one transaction with two legs; balances of both accounts change; spend reports exclude it.
3. Every transaction belongs to exactly one budget period of its profile at any time.
4. Soft-deleted entities never appear in pickers or totals; restore returns them unchanged.
5. Minor-unit integers everywhere; no floating point in money math.
6. Every mutation is an Event; the materialised state is reproducible from events.

---

## 7. Architecture and stack

- Mobile: Flutter (Dart) single codebase for Android, iOS, web; platform channels for notification listener (Android), Shortcuts/share extensions (iOS), widgets (Jetpack Glance, WidgetKit), watch apps (native thin clients).
- State: a domain layer (pure Dart) with use-cases; UI never touches SQL. Riverpod or Bloc for state; freezed models.
- Storage: SQLite via Drift with SQLCipher; event log table + materialised views; FTS5 for search.
- ML on device: ML Kit / Vision for OCR; platform speech APIs with a custom grammar post-processor; a small gradient-boosted classifier for category suggestions trained per profile on device.
- Crypto: libsodium (flutter_sodium); keys in Keystore/Secure Enclave.
- Backend: Go services; Postgres (metadata, entitlements, invitations); S3-compatible blob store for ciphertext; regions: us-east, eu-west, ap-south (Mumbai), ap-southeast (Singapore); user picks residency.
- Connectors: a `BankConnector` interface with one adapter per provider; runs server-side; delivers normalised encrypted transactions to the client; tokens never leave the connector service.
- Rates: daily ECB/open-rates feed cached 400 days on device; gold prices per pack.
- Web: Flutter web for the full app; a lightweight static page for advisor links and group guests.
- API: REST + webhooks, OAuth2 for third parties; OpenAPI spec in `/api`.
- CI: unit, widget, golden, integration (patrol), property tests for period math and money; pseudo-locale and RTL screenshot suites; size and startup budgets gated.
- Repos: monorepo `/app` (Flutter), `/server` (Go), `/packs` (regional data, JSON with schema), `/importers`, `/docs`.

---

## 8. Non-functional requirements

- NF-01 Cold start ≤ 1.5 s, warm ≤ 0.5 s on a 2022 budget Android (4 GB RAM); first frame on web ≤ 2 s on 3G.
- NF-02 APK/AAB download ≤ 30 MB; iOS ≤ 60 MB; language, OCR and voice packs downloaded on demand.
- NF-03 Smooth 60 fps lists with 100k transactions; search results < 150 ms.
- NF-04 Crash-free sessions ≥ 99.7%; ANR rate < 0.1%.
- NF-05 Battery: notification listener and background sync < 1% daily drain.
- NF-06 Accessibility: WCAG 2.2 AA; dynamic type to 200%; full screen-reader labels; focus order; 44 pt targets; no colour-only meaning; reduce-motion respected; high-contrast theme.
- NF-07 Privacy by default: all processing of personal data on device unless a connector requires otherwise; data minimisation documented per feature in `PRIVACY-MATRIX.md`.
- NF-08 Observability: client logs are local and user-exportable; server logs contain no plaintext user data.
- NF-09 Store policy: no READ_SMS; notification listener disclosed; Data Safety and App Privacy forms generated from the privacy matrix.

---

## 9. UX standards

- UX-01 Every list has designed empty, loading, error and offline states with one next action.
- UX-02 Undo over confirmation: destructive actions execute immediately with a 5 s undo; only account/profile deletion uses typed confirmation.
- UX-03 Four tabs (Home, Budgets, People, Insights) plus a centre add button; Accounts and Settings from the avatar; nothing more than two taps from Home.
- UX-04 Forms: inline validation on blur; never lose input on rotation, background or republish; drafts kept.
- UX-05 Money entry: calculator keypad; locale separators; currency switcher inside the field; haptic tick on chips.
- UX-06 Colour: one accent; amber for near, orange for over; semantic colours never reused for categories; dark and light designed, not inverted.
- UX-07 Navigation state survives process death; deep links restore full context (period, envelope, filters).
- UX-08 Tablet and landscape: two-pane layouts (list + detail); web keyboard navigation.
- UX-09 Share-sheet intake lands in the review queue with a toast linking to it.
- UX-10 Copy: plain words, active voice, numbers with units; neutral language around debt; no exclamation marks in system text.
- UX-11 Onboarding never asks for a permission before the feature that needs it.

---

## 10. Quality and definition of done

### 10.1 Definition of done (per feature)
1. Requirements and ACs linked in the PR; tests cover ACs; CRUD matrix row green.
2. Works offline; sync round-trip verified on two devices; conflict test passed.
3. Localised strings in all launch languages (machine draft allowed, native review before release); RTL screenshot passes; pseudo-locale passes.
4. Accessibility checklist passed with a screen reader on Android and iOS.
5. Empty/loading/error states present; undo verified.
6. Performance budgets met on the device matrix; no new permissions without privacy-matrix entry.
7. Analytics events reviewed for data minimisation.
8. Docs: user help article drafted; `CHANGELOG.md` updated.

### 10.2 CRUD and lifecycle matrix (minimum per object)

| Object | Create | Read/detail | Update all fields | Delete (soft) | Restore | Archive | Reorder | Merge | History | Bulk | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Transaction | ✓ | ✓ | ✓ | ✓ | ✓ | – | – | dupes | ✓ | ✓ | ✓ |
| Split | ✓ | ✓ | ✓ | ✓ | with parent | – | ✓ | – | ✓ | – | ✓ |
| Account | ✓ | ✓ | ✓ | ✓ reassign | ✓ | ✓ | ✓ | – | ✓ | – | ✓ |
| Category | ✓ | ✓ | ✓ | ✓ reassign | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Tag | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | – | ✓ |
| Payee | auto/✓ | ✓ | ✓ | ✓ reassign | ✓ | – | – | ✓ | ✓ | – | ✓ |
| Rule | ✓ | ✓ | ✓ | ✓ | ✓ | disable | ✓ | – | ✓ | – | ✓ |
| Envelope | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | – | ✓ | – | ✓ |
| Budget period | auto | ✓ | limits | – | – | – | – | – | ✓ | – | ✓ |
| Goal | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | – | ✓ | – | ✓ |
| Recurring | ✓ | ✓ | ✓ this/future | ✓ | ✓ | pause | – | – | ✓ | – | ✓ |
| Occurrence | auto | ✓ | ✓ | skip | unskip | – | – | – | ✓ | – | – |
| Loan | ✓ | ✓ | ✓ | ✓ | ✓ | close | – | – | ✓ | – | ✓ |
| Lending entry | ✓ | ✓ | ✓ | ✓ | ✓ | settle | – | – | ✓ | – | ✓ |
| Group / expense | ✓ | ✓ | ✓ | ✓ | ✓ | archive | – | – | ✓ | – | ✓ |
| Remittance | ✓ | ✓ | ✓ | ✓ | ✓ | – | – | – | ✓ | – | ✓ |
| Circle / contribution | ✓ | ✓ | ✓ | ✓ | ✓ | close | ✓ turn | – | ✓ | – | ✓ |
| Attachment | ✓ | ✓ | replace | ✓ | ✓ | – | ✓ | – | – | – | ✓ |
| Profile | ✓ | ✓ | ✓ | ✓ typed | – | – | ✓ | – | ✓ | – | ✓ |
| Space / member | ✓ | ✓ | ✓ role | ✓ leave/remove | – | – | – | – | audit | – | ✓ |
| Invitation | ✓ | ✓ | – | revoke | – | – | – | – | ✓ | – | – |
| Alert / watchlist | auto/✓ | ✓ | ✓ | dismiss | – | mute | – | – | ✓ | – | – |
| Saved search / report | ✓ | ✓ | ✓ | ✓ | ✓ | – | ✓ | – | – | – | ✓ |
| Sync link | ✓ | ✓ | re-consent | disconnect | – | – | – | – | ✓ | – | – |
| Device / session | auto | ✓ | rename | sign out | – | – | – | – | ✓ | – | – |

### 10.3 Test plan
- Unit: money math, period math (property-based across cycles, DST, month ends), rules, dedupe, importers (fixtures per bank), parsers per regional pack (fixture notifications and emails).
- Integration: sync between two simulated devices with conflicts; backup/restore; migration importers end-to-end.
- UI: golden tests per screen in light/dark/RTL/large text; patrol flows for the top 20 journeys.
- Security: key handling review, static analysis, dependency audit, external pen test before launch.
- Usability: 10 users per launch region on the 3-second add and the first-week retention flows.

### 10.4 Device matrix
Android 9–16 on 4 GB and 8 GB devices (one budget, one mid, one flagship), tablets; iOS 16–20 on iPhone SE, 13, 16 and iPad; web on Chrome, Safari, Firefox, Edge; Wear OS 4+, watchOS 10+.

---

## 11. Delivery plan

| Milestone | Scope | Exit criteria |
| --- | --- | --- |
| M0 Foundations (weeks 1–6) | Monorepo, domain layer, event log, SQLite+SQLCipher, money and period libraries, regional pack schema with 5 packs, design system, CI with golden/pseudo-locale/RTL | Property tests green; add/edit/delete a transaction offline on Android and iOS; CRUD matrix tool scaffolded |
| M1 Core (weeks 7–14) | Transactions (all types), accounts, categories, payees, tags, budgets with all cycles, goals, recurring, search, insights v1, settings, app lock, export/import JSON/CSV, 10 languages | §2 items 1–16, 20–28, 30–38 closed; TestFlight and internal Play track |
| M2 People and sync (weeks 15–22) | Accounts/login, profiles, spaces, roles, invites, audit log, groups, lending, E2EE sync, backup/restore, devices, history and trash | Two-device conflict suite green; §2 items 3, 4, 17, 24, 25, 39 closed; closed beta 2,000 users across 5 regions |
| M3 Capture (weeks 23–30) | Notification parsers for top 30 apps across packs, share sheet, email forwarding, OCR, voice, importers incl. migration from 12 apps, rules engine with learning, review queue | ≥ 70% of beta users' transactions captured without typing; parser accuracy ≥ 95% on fixtures |
| M4 Alerts, remittances, circles, debt, subscriptions, widgets, watch, web app, 30 languages, all launch packs | §4.7–4.10, 4.13, 4.20 | Public launch in 10 regions; D30 retention ≥ 30% |
| M5 Plus and bank sync | Connector service with 6 providers, consent UX, Plus billing on store and web, advisor links, business book | Sync live in US, UK/EU, IN, AU, BR, PH; conversion ≥ 2% |
| M6 Depth | Coach, Sankey/forecast scenarios, investments, tax reports, API and webhooks, Shortcuts/Tasker, community merchant map | Feature benchmark tab fully green |

---

## 12. Name and brand

Recommendation: **Pouch** (replaced Saldo on 15 Nov 2026: Saldo reads as foreign in India and East Asia; Pouch is a plain English word understood in every launch market).
- A pouch is where people keep money in every culture; one syllable, five letters, spelled as said, no translation needed. Word-of-mouth test: "Download Pouch" is unambiguous in English, Hinglish, Taglish and Spanglish.
- Low collision in finance app stores at the time of writing; verify trademark classes 9, 36, 42 before launch.
- Brand line: "Pouch. Know what is safe to spend."

Alternatives, in order:
1. **Penny** — universally understood coin word, friendly, two syllables; crowded mark, needs a distinctive logo and a qualifier for stores.
2. **Abacus** — the counting tool every Indian, Filipino and Western schoolchild knows; descriptive; medium trademark risk from B2B expense tools.
3. **Tab** — "keep tabs on", "pick up the tab"; very short; generic and hard to search for.
4. **Kitty** — the shared pot; warm; crowded in app stores. Rejected: Saldo (not understood in India and East Asia), Tally (blocked by Tally Solutions), Ledger (blocked by Ledger SAS), Jar (Indian gold-savings app), Mint (Intuit mark).

Before adopting any name: trademark search in classes 9, 36, 42 in US, EU, UK, IN, PH, BR, AU, JP; app-store name search; domain (.app, .com) and handles; native-speaker check in all launch languages. Record the result in `DECISIONS.md`.

---

## Appendix A. Default universal category tree (regional packs localise and extend)
Income: Salary, Business, Freelance, Gifts received, Interest and dividends, Refunds, Remittance received, Other income.
Expense: Food and groceries (Groceries, Markets, Delivery); Eating out (Restaurants, Cafés, Takeaway); Housing (Rent, Mortgage, Maintenance, Society/HOA); Utilities (Electricity, Water, Gas, Internet, Mobile); Transport (Public transit, Ride-hailing, Fuel, Parking, Tolls, Vehicle service); Health (Doctor, Pharmacy, Insurance, Fitness); Family (Childcare, School, Allowance, Elder care, Remittance sent); Shopping (Clothing, Electronics, Home, Personal care); Entertainment (Streaming, Events, Games, Hobbies); Travel (Flights, Hotels, Local); Education; Pets; Gifts and donations (Gifts, Religious, Charity); Financial (Fees, Interest, Taxes, Insurance); Loans (Given, Repaid to me, EMI, Repayment); Savings and investments (contributions, excluded from spend); Transfers (excluded); Adjustments (excluded); Unrecorded cash (reportable); Uncategorised.

## Appendix B. Regional pack schema (JSON)
`{ id, name, locales[], currency, number_grouping, pay_cycle_default, fiscal_year_start, holidays[], festival_windows[], circle_names[], payment_apps[{package, parser_id}], bank_apps[], aggregator_provider, settlement_schemes[], typical_amount_chips[], cancellation_guides[], price_points{plus_monthly, plus_yearly, plus_lifetime}, privacy_text_ref }`

## Appendix C. Importer presets (first wave)
YNAB (register CSV + budget CSV), Monarch (transactions CSV), Mint archive, Money Manager (Excel), Spendee, Wallet, Splitwise (group CSV), Bluecoins (CSV/QIF), Toshl, Goodbudget, EveryDollar, PocketGuard, Actual (export zip), Firefly III (CSV), Lunch Money, generic bank CSV with mapper and saved presets per bank.

## Appendix D. Top 20 user journeys for UI tests
Add spend in 3 taps; edit and split; transfer; income on payday with auto-assign; create envelope and set rollover; over-par cover-from; reconcile cash; review captured notification; import CSV; invite partner and share an envelope; add group expense and settle; record lending and partial settle; record remittance both sides; set up a circle; schedule a bill and pay it; search natural language; change pay cycle and verify re-attribution; export and reimport; restore on a new device; delete account.
