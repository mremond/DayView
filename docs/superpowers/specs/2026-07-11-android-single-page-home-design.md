# Android single-page home + creation popups

**Date:** 2026-07-11
**Status:** Approved, pending implementation plan

## Problem

On a phone (the compact layout of `DayViewScreen`), the home screen is a vertical
scroll that stacks the header, the countdown circle, the Global Goal panel (two
always-expanded text fields), a headline sentence, the **entire Focus creation
form** (intention field + duration stepper + start button), and a "% of day"
indicator. The Focus form and the Goal panel are the two bulkiest blocks, forcing
the default screen to scroll. We want the **default (idle) phone screen to fit on a
single page** and to improve usability by moving bulky editing into popups.

## Scope

- **Compact/phone layout only.** All changes live in the `else` (compact) branch of
  `DayViewScreen` in `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`.
  The `wide` branch (>= 780dp, desktop) is left unchanged.
- The compact branch is width-gated, not platform-gated, so the popups must remain
  functional if a desktop window is shrunk below 780dp. This is why they use
  Compose Multiplatform's material3 `ModalBottomSheet` (available in commonMain)
  rather than an Android-only component.

## Non-goals

- No redesign of the wide/desktop two-column layout.
- No changes to `DayViewController` business logic or to `App.kt` action wiring.
- No new `DayViewDestination`; popup visibility is transient local UI state.
- No new Compose UI-test harness (the repo has none today).

## Design

### Architecture

- Which popup is open is held in local state within the compact composable:
  `remember { mutableStateOf<CompactSheet?>(null) }` where `CompactSheet` is a small
  enum (`FOCUS`, `GOAL`). No controller or navigation-destination changes.
- Popups reuse the existing `DayViewScreenActions` callbacks
  (`changeFocusIntention`, `changePomodoroDuration`, `startPomodoro`,
  `changeGoalTitle`, `changeGoalDeadline`, `commitGoalDeadline`, ...).
- `verticalScroll` is retained on the compact page as a safety net (small devices,
  large system font scaling, taller active-session states), but the default idle
  content is sized to fit without scrolling on a typical phone.

### Compact home composition (idle / default state), top to bottom

1. **Header** — unchanged (DAYVIEW · RÉGLAGES; MINI is desktop-only, absent here).
2. **Countdown circle** — hero, unchanged, with a single condensed subtitle line
   underneath (a shortened form of the existing "Voyez le temps / Gardez le cap"
   philosophy message) instead of a full text block.
3. **Global Goal — compact line.** Read-only summary row, e.g.
   `OBJECTIF · Livrer la v2 · reste 12 h` (reusing `formatGoalWorkingHours`).
   Empty state: `+ Définir un objectif`. Tapping the row opens the Goal sheet.
4. **Focus entry point.** A single prominent button `DÉMARRER UN FOCUS` that opens
   the Focus sheet. If a `lastClosure` exists, a small chip above it
   (`FOCUS CLÔTURÉ · TERMINÉ` / `AVANCÉ` / `À REPRENDRE`).
5. **% of day available** — the existing dot + `X % de la journée disponible` line.

### Popups (Material3 `ModalBottomSheet`)

Bottom sheet chosen over a centered dialog: it is the native Android idiom for
create/edit flows and cooperates with the keyboard via `imePadding` for the text
fields.

- **Focus sheet** — contains today's IDLE `FocusPanel` body: the
  "À la fin de ce Focus, j'aurai…" label, the intention text field, the duration
  stepper (−/value/+), the `DÉMARRER LE FOCUS` button (enabled only when the
  intention is non-blank), and the "write an intention to start" hint. Starting a
  Focus dismisses the sheet.
- **Goal sheet** — the objective title + deadline fields with the existing
  validation and format-error messaging.

### Active / paused Focus (inline)

When a Focus is active or paused, slot 4's entry button is **replaced inline** by
the running session UI — intention, live clock, progress bar, stop button, the
break/pause UI, and the drift-reminder and resume-ritual overlays. No popup is
involved once a session is running. This block can be taller than the idle button;
that is acceptable because "fit one page" targets the default idle screen and the
scroll safety net covers the rest.

## Testing

- `DayViewController` logic is unchanged, so existing `*Test.kt` suites remain green.
- Any new pure formatting (e.g. the compact Goal summary line) is extracted into a
  testable helper and covered in the existing test style.
- The UI itself is verified by running the app on a phone/emulator; no UI-test
  harness is added.

## Open questions

None. Bottom sheet and the condensed subtitle were both confirmed.
