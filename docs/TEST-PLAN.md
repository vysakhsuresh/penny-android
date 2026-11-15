# Penny — phone test plan (1.0.0-beta1)

Device: your phone (Moto g84 5G, Android 15) over USB. Optional: a second Android phone for sync and spaces, a Wear OS watch.
Time: about 2 hours for sections 1–12, 1 more hour for 13–17. Mark each step ✅ / ❌ and note anything that feels wrong.

## 0. Setup (once)
1. On the phone: Settings → About phone → tap Build number 7 times → Developer options → turn on **USB debugging**. Connect USB and accept the prompt.
2. In the project folder: `./gradlew :app:installDebug` (phone app, id `app.penny.debug`). For the watch: `./gradlew :wear:installDebug` with the watch connected over Wi-Fi debugging.
3. If an older Penny build is on the phone, uninstall it first (the database moved to version 2).
4. Optional sync server on your laptop: `cd server && go run . -dev -store=memory -region in` (prints sign-in codes to the console). On the phone use `http://<laptop-ip>:8080` as the sync server — note: debug builds allow it only over your LAN; production uses https.

## 1. First run and onboarding (OB-01…OB-04)
1. Open Penny. Welcome screen shows the promise and 4 points. Tap **Get started**.
2. Region: your region is first; search "phil" → Philippines appears. Pick India; note the sample shows ₹12,34,567.89.
3. Pay cycle: default says "(usual here)". Pick Monthly. Cash: enter `1500+250` → finish. Home opens in under a minute from launch.
4. Repeat later with **Explore with sample data** (Settings → Profiles → add, or reinstall) to see filled screens.

## 2. Home and safe to spend (BU-07, AL-02, BU-08)
1. Safe to spend = income − bills still due − goal set-asides − spent. Check against your own numbers.
2. With no income this period the pill says "No income recorded this period yet" (never "On pace").
3. Arrows move to past/next periods; title changes to "Was safe to spend" / "Planned"; "Back to this period" returns.
4. Eye icon hides all amounts (also in widgets). Avatar opens Settings. Bell opens Alerts. Search opens Activity.
5. Swipe a transaction left → deleted with Undo for 5 s; swipe right → category sheet.

## 3. Add transactions (TX-01…TX-16)
1. Plus button → quick add: type `120+80` → shows "= ₹200.00". Tap a smart category, Save (3 taps).
2. Tap **More** → full form: Expense / Income / Transfer, then Refund / Adjustment / Opening balance.
3. Transfer between two currencies: enter the received amount → implied rate shows.
4. Split this transaction: 3 parts by amount, then by percent; "Left to split" reaches 0; Save. Remove a part.
5. Add a photo/PDF attachment; add tags with commas; set a place; set Pending; Private; leave out of budget.
6. Pick a future date → "Future date: this will count as scheduled".
7. Save, reopen, change every field, Save → History shows each change with before → after; Revert to version 1; Undo the revert.
8. Same account + same amount within a day → "Looks like a duplicate": try Merge and Keep both.
9. Mark Reconciled, reopen → locked banner; Un-reconcile and edit.
10. Save as template "Coffee"; it appears as a ★ chip in quick add and fires in one tap.
11. Validation: JPY account rejects `10.5`; KWD accepts 3 decimals; empty amount shows a clear message.

## 4. Activity, search, bulk (SR-01…SR-06, TX-04)
1. Search "coffee last month over 200" → "Understood: last month · over 200".
2. Filters: dates, types, accounts, categories, tags, amount range, attachment, private; Sort by amount ↑↓.
3. Save search "Reimbursable this quarter" → appears as a chip and in Insights → reports.
4. Long-press two rows → bulk change category / account / tag / date / merge / delete → one Undo reverses all.

## 5. Accounts (AC-01…AC-08)
1. Add bank, card (limit, statement day, due day, min due %, APR), wallet "via" a bank, asset with grams.
2. Card detail shows statement balance, unbilled, minimum due, utilisation, due in N days.
3. Set balance → adjustment with Undo. Cash → Count my cash → difference in "Unrecorded cash".
4. Reconcile → cleared items become reconciled. Archive → hidden from pickers, still in reports.
5. Delete an account with transactions → choose Move to another account → typed confirmation → done; check Trash.
6. Reorder in Accounts (Reorder → arrows). Net worth total updates; Record value on an asset.

## 6. Categories, tags, payees, rules (CAT, PY, RU)
1. Add a subcategory (3 levels max; a 4th is refused). Rename, archive, reorder, merge A into B → transactions moved; Undo.
2. Delete a used category → reassign dialog. Restore defaults → only missing ones come back.
3. Tags: rename (transactions follow), merge, archive. Payees: open a payee page (totals, trend), rename, merge.
4. Change the category of the same payee twice → a learned rule appears as draft; third time → on.
5. New rule: payee contains "uber" → category Transport; preview count shows; "also change past" applies; Conflicts card when two rules overlap.

## 7. Budgets (BU-01…BU-12)
1. Budgets → ⋮ → Budget templates → Salaried with income → envelopes created.
2. Assign (pencil) → available to assign changes. Fill from targets. Copy last period. Start from zero.
3. Overspend an envelope → orange "over", Cover from another envelope → money moved, shown under "Money moved".
4. Rollover modes (positive, both, capped) carry correctly into the next period; year view shows 12 columns.
5. Past period is read-only until "Edit anyway". Change pay cycle in Profile → history regroups; Undo.

## 8. Goals, bills, debt (GO, RC, DB)
1. Goal with date: per-period need shows; add/take money; pause; complete (calm card); delete returns money.
2. Emergency fund: suggested target from essentials.
3. Bills: add rent monthly on the last day; Streaming as subscription; mark paid (transaction created), skip, unskip, change this one only, "this and future".
4. Calendar month view with totals per day; Subscriptions shows yearly cost and price increase; draft cancellation message.
5. Detected "These look like they repeat" after 3 similar monthly expenses → Add.
6. Loan 1,00,000 at 10% for 12 months → monthly payment 8,791.59; prepayment what-if; Add EMI as a bill. Payoff planner: avalanche vs snowball order.

## 9. People (PE, RM, CI)
1. Record lending to Sam (with interest and due date), partial payback, Settle everything, Draft a reminder (share sheet).
2. Group "Goa trip" with 3 people: expenses equal / exact / percent / shares / by item; balances sum to zero; Simplify; Settle with UPI link (opens your UPI app) or mark as paid; Export CSV; dispute flag.
3. Money sent home: sent 1000 AED, fee 15, received 22,300 INR, mid-market 22.70 → true cost ≈ 3.26%.
4. Savings circle with you and two others: rounds, tick who paid, record payout.

## 10. Insights and coach (IN)
1. Ranges: pay period / month / quarter / year; arrows; vs previous and last year.
2. Tap a donut legend row or a bar → Activity filtered to those transactions. Table view toggle.
3. Heatmap day tap, money flow, net worth line, forecast with what-if cut slider.
4. Coach: "How much did I spend on food this month?" → answer + the transactions behind it; tones.
5. Share a summary → share sheet text.

## 11. Capture (CP)
1. Settings → Capture → Read payment notifications → explanation → system page → allow Penny.
2. Make a small UPI/GCash/etc. payment → item appears in Review with source and confidence; Add / Edit / Ignore / always ignore.
3. Share an SMS/message text or a receipt image to Penny → "Added to your review list".
4. Quick add → camera icon → scan a receipt → total, date, shop filled; saved with the photo attached.
5. Quick add → mic → "250 on groceries yesterday from cash".
6. Import: Data and backup → Import from a file → a bank CSV (check columns, date format, number style) → preview with duplicates → Import → one Undo removes it.
7. Confirm Penny never asks for SMS permission (Settings → Apps → Penny → Permissions).

## 12. Settings, security, data (ST, SE, SY)
1. Appearance: Light, Dark, True black, High contrast; accent; text size 200% (Home number must stay readable); compact lists; reduce motion.
2. Phone language: change to Hindi, Arabic (RTL mirrored), Japanese — app follows; Settings → Language opens per-app language.
3. Security: App lock with PIN + fingerprint; background for longer than the timer → locked; recent apps shows no amounts. Duress PIN opens a different sample book. Block screenshots.
4. Alerts: phone notifications (explanation before permission), quiet hours, per-type switches, watchlist. Push an envelope to 85% → alert in Alerts (and a silent notification outside quiet hours).
5. Exchange rates (Profile → Exchange rates): Refresh now on Wi-Fi; add your own rate; a USD account converts on Home.
6. Data: Export JSON and CSV zip; Restore the JSON (idempotent: restoring twice adds nothing). Choose a backup folder → Back up now.
7. History of changes (all objects), Trash (restore, delete forever with 5-s undo).
8. Profiles: add a Business profile (VAT, distance), switch, delete with typed confirmation.
9. Delete everything → app restarts at onboarding.

## 13. Sync, spaces, bank (needs the relay)
1. Sync and banks → email → code from the server console → write down the 24 words → Sync now.
2. Second phone: same email → type the phrase → data appears. Edit the same transaction on both offline, then sync → both phones show the same final value; History marks the conflict.
3. Shared spaces → Create → Invite as Editor → open link/code on phone 2 → Join → space appears after phone 1 syncs.
4. Remove/leave member; Viewer cannot add (server rejects push).
5. Bank: with the dev server, connect **Sandbox** → accounts created → Sync now → transactions arrive, pending → cleared; Disconnect.
6. Email forwarding: Get address → POST a test mail to `/v1/mail/inbound` (see server README) → item in Review.
7. Advisor link / group web view: open the link in a browser → read-only table.
8. Devices: sign out the other phone remotely.

## 14. Widgets, tile, shortcuts, automation (WG)
1. Add the 3 widgets; values match Home; hidden when "hide amounts" or app lock is on.
2. Quick Settings tile "Add to Penny"; launcher long-press shortcuts.
3. `adb shell am start -d "penny://add?amount=12500&category=Groceries"` opens a prefilled form.
4. Automation → allow apps → `adb shell am broadcast -a app.penny.action.ADD_TRANSACTION -p app.penny.debug --es amount 99 --es payee Test` adds a transaction.
5. Webhook to a https request bin → new transaction posted with X-Penny-Signature.

## 15. Watch (WG-02)
Safe to spend shows; tap a template → added on the phone; "Say a spend"; tile and complication show the number.

## 16. Plus and tips (MO)
Play Billing works only for builds installed from a Play test track with products set up; on a sideloaded debug build the buttons report the store as unavailable — that is expected.

## 17. Quality pass on each screen
- TalkBack: every icon button is announced; amounts read as money.
- 48 dp touch targets; nothing clipped at 200% text; RTL mirrored; dark and light both readable; never red for money.
- Rotate on a form mid-edit: input stays. Kill the app from recents on a form and reopen: draft restored.
- Airplane mode: everything except sync, rates, bank and email works.

## Known limits in this build
- Translations are machine drafts; a few newest strings show in English.
- Bank providers other than SimpleFIN and Sandbox need commercial contracts and keys.
- Plus purchases are not yet verified with Google Play on the server.
- Attachments stay on the phone (no attachment sync yet).
