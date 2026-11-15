# Penny web prototype

Open `index.html` in a browser (no build step). It is the interactive reference for the Android app: every screen, form, validation rule, alert and permission behaviour described in `docs/SPEC.md` is exercised here, including the 40-gap checklist in the side panel.

Files: `packs.js` (regional packs, currencies, strings, default categories, gap list), `core.js` (money, periods, event-sourced store, encryption, rules, alerts, importers, seed data), `ui.js` (routing, sheets, form engine, lock screen), `screens.js` and `screens2.js` (screens).

Treat it as behaviour documentation, not as code to port line by line.
