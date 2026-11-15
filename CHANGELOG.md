# Changelog

## 1.0.0-beta1 (unreleased)
- Budgets on any pay cycle: envelopes with rollover, targets, cover-from, available to assign, templates, year view.
- Every transaction type and field: splits, attachments, place, status lock, templates, duplicates, bulk edit.
- Quick add with calculator keypad, smart categories and templates; voice add; receipt scanning on the phone.
- Accounts with card cycles, reconcile, count cash, valuations; categories (3 levels), tags, payees, rules that learn.
- Goals, bills and subscriptions with calendar and detection, loans with amortisation and a debt payoff planner.
- People: lending, group splitting with simplified debts and settle-up links, money sent home, savings circles.
- Insights: summary, categories, trend, heatmap, money flow, net worth, forecast with what-ifs, on-device coach.
- Capture: payment notifications (never SMS), share sheet, email forwarding, review queue, 15+ import formats and apps.
- End-to-end encrypted sync, shared spaces with roles and invites, recovery phrase, encrypted folder backups.
- Bank connections (Plus), Plus and tip jar through store billing, exchange rates, 44 regional packs.
- App lock with PIN, biometrics and duress PIN; screenshot blocking; delete everything.
- Widgets, Quick Settings tile, shortcuts, Wear OS app with tile and complication, automation intents and webhooks.
- Help, guides, buy me a coffee, about and trust pages; 30 languages (machine drafts).

### Fixed in the system sweep
- App lock keeps your place (forms, back stack, camera results); a killed app always starts locked; PIN back-off survives restarts.
- Deep links, widgets, the tile and shortcuts work while the app is already open; widget taps always open Penny.
- Double-tapping Save or Back no longer saves twice or leaves a blank screen; failed saves say so.
- "Delete forever" in Trash completes even if you rotate or leave the screen.
- Scheduled bills and goal contributions never post twice or come back after you delete them; all profiles are covered.
- Two genuine same-amount payments are no longer merged as duplicates.
- Safe to spend counts split lines and converts foreign-currency bills and goals.
- Sync no longer skips batches it cannot read; restored data syncs.
- Deleting a remittance, loan entry, group expense or circle payment removes its transaction too.
- Quiet-hour alerts are delivered when quiet hours end.
- Crashes fixed when no app can open a payment link, the camera or notification settings, and on unreadable import files.
- Amounts shrink to fit instead of being cut off at large text sizes; swipe actions are available to TalkBack.

## 0.1.0-m0
- Foundations: pure-Kotlin core (money, periods, event log), SQLCipher-encrypted Room store, 14 regional packs.
- Onboarding, Home with safe to spend, transactions with undo, trash and history, region switch, `penny://add`.
