# macOS Native — The focus exit: closure ritual, exit toll, open detour (Path B, Phase 12a)

## Context

The asymmetric focus lifecycle — *entering is free, finishing is a choice, fleeing costs a
name* — landed in `:core` and Compose across tasks 1–12 of the
[asymmetric-focus work](2026-07-18-asymmetric-focus-design.md). The native SwiftUI app was
deliberately left on the old three-outcome model with a stop-gap `OVERTIME` case, and the
Task 13 review itemized the resulting gap as five PORT rows on the parity checklist.

One of those rows is not a missing feature but a **live regression**. `DayViewSession.stopFocus()`
calls `closePomodoro(TO_RESUME)` naming no detour. Since `earlyExitRequiresDetour` landed, that
call is a silent no-op before the term, so **the native app's Stop button does nothing** until a
session reaches its end. The behaviour is documented in the bridge's own KDoc as an accepted gap;
it is the first thing this phase closes.

Phase 12 is split (approved): **12a (this spec)** is the exit — the closure ritual, the exit
toll, and the open detour the toll creates. **12b (later)** is the entry and the élan — free
entry, the 5-minute preset, and the `OVERTIME` display.

## The scope discovery: the exit toll needs a surface the native app never built

The exit toll does not record a retroactive detour. It starts an **open detour** — the running
stopwatch — via `startOpenDetour`, and the controller's closure chains "close, then start the
detour" in that order.

Phase 9a deliberately deferred open detours natively ("Declare-after-the-fact only"), so
`startOpenDetour`/`stopOpenDetour` are unbridged, `TodaySnapshot` carries no open-detour field,
and no Swift view renders one. Porting the toll alone would mean: the user leaves a session,
names the pull, an invisible stopwatch starts, and nothing in the app shows it or stops it.
Compose has a dedicated `OpenDetourPanel` (elapsed clock, motif, description, stop button) in
both windows.

**Decision (approved):** 12a includes the minimal open-detour surface. The toll is unusable
without it, and 9a's deferral becomes blocking exactly here.

## Goals

- Every stop routes through a closure ritual, in every non-IDLE status — closing the regression.
- The ritual carries the (optional, pre-filled) intention and, when the exit costs a name, the
  detour capture that pays it.
- A running open detour is visible and stoppable in both windows.

## Non-Goals (12b or later)

- Free entry: the intention is still required to Start, and there is no 5-minute preset.
- `OVERTIME` display: `MiniView.focusCard`'s hardcoded `"Break · …"`, the `"OVERTIME"` folded
  into the `"BREAK"` case, and the resume ritual's `"\(pomodoroClock) left to stay on track."`
  reading "+12 min left to stay on track." all stay as they are.
- The overtime closure reminder (needs the notification plumbing deferred in 10b).
- Starting an open detour from the detour panel (only the exit toll opens one in 12a).
- French localization — native is hardcoded EN, and i18n is one cross-cutting phase near the end.

## Decisions (from brainstorming)

1. **Split 12a exit / 12b entry**, mirroring the spec's own framing and the project's
   5a/5b, 9a/9b, 10a/10b rhythm.
2. **A modal `.sheet`, not an inline unfold.** The checklist's strategy is "presentation:
   native idiom wins", the app already uses sheets for detour capture, detour list and the mini's
   intention, and the mini window (360×520 default, 200×300 min) has no height for an unfold.
3. **The open-detour surface ships in 12a** (above).

## Architecture

### `:core` — the bridge only; the controller does not change

Every behaviour this phase needs already exists in `DayViewController`. Only the Swift-facing
facade moves.

**`TodaySnapshot` gains five fields:**

```kotlin
val earlyExitCostsName: Boolean,   // leaving now with PROGRESSED/TO_RESUME requires a motif
val detourOpenRunning: Boolean,
val detourOpenCategory: String,
val detourOpenDescription: String,
val detourOpenClock: String,       // "MM:SS" elapsed, "" when none is running
```

`earlyExitCostsName` is computed as
`earlyExitRequiresDetour(now, pomodoroEnd, FocusClosureOutcome.PROGRESSED, openDetourRunning)`.
**One boolean is sufficient and not a simplification:** the predicate's only outcome-dependence
is `outcome != COMPLETED`, so `COMPLETED` is always free and the other two always share a
verdict. Passing `PROGRESSED` as the representative outcome is exact, not approximate.

Because the predicate reads `now < end`, the boolean falls to false on its own at the term and
when an open detour is already running — which is what makes the "toll lifts under your feet"
behaviour below fall out of the snapshot rather than needing Swift-side timing logic.

`detourOpenClock` reuses the existing `formatElapsedClock(openDetourElapsed)`, following the
Kotlin-computed-label convention (presentation strings computed once in `toTodaySnapshot`, never
per Swift view). It is `""` when no detour is open.

**`DayViewSession`:**

```kotlin
fun closeFocus(outcome: String, intention: String, detourCategory: String, detourDescription: String)
fun stopOpenDetour()
```

- `closeFocus` **replaces** the current `closeFocus(outcome:)` at full arity rather than growing
  default parameters: phase 7b established that Kotlin default parameters change the exported
  Swift selector, so defaults would silently break Swift call sites. The existing string→enum
  mapping (unknown → `COMPLETED`, no FFI throw) is unchanged.
- `stopOpenDetour()` delegates as Compose's `App.kt` does:
  `controller.stopOpenDetour(controller.stateFlow.value.openDetourCategory)`.
- **`stopFocus()` is deleted.** It is the regression: under the asymmetric model there is no
  outcome-less exit, and the spec removed `stopPomodoro()` for the same reason. Its five Swift
  call sites — `RingView` (ACTIVE, BREAK/OVERTIME, and the resume ritual) and `MiniView` (two
  status branches) — all become "present the sheet", as does `TodayModel`'s wrapper.

### Swift — the closure sheet

New `macos/DayView/FocusClosureSheet.swift`, presented by Stop from any non-IDLE status:

- A single-line intention field, pre-filled from `snapshot.focusIntention`, optional.
- The three outcome buttons (Completed / Progressed / Resume later), keeping the existing
  palette tints from `FocusClosureButtons`.
- Tapping an outcome that costs a name **does not close**: the sheet unfolds the exit capture —
  a motif field, the `recentDetourCategories` chips, an optional detail field — and only Confirm
  leaves, calling `closeFocus` with all four values. Confirm is disabled while the motif is blank
  (the controller would silently refuse it otherwise — the same silent-refusal class of bug this
  phase exists to fix).
- Tapping an outcome that is free calls `closeFocus(outcome, intention, "", "")` and dismisses.
- Cancel dismisses; the session continues untouched.

**The toll can lift under the user's feet.** If `earlyExitCostsName` becomes false while the
capture is open — the term arrives mid-capture — the sheet collapses back to the outcome row, so
nobody is left naming a pull they no longer owe. In SwiftUI this is `.onChange(of:)` on the
snapshot's boolean; per phase 10b's lesson, it pairs with `.onAppear` since the sheet can be
presented already in either state.

`FocusClosureButtons.swift` and `RingView`'s inline `closureSection` are removed — the sheet is
now the one closure surface, and the shared-button view exists only to be shared by the two
inline usages that no longer exist.

The resume ritual's Stop (`RingView.swift:253`) dismisses the ritual and presents the sheet
rather than calling the deleted `stopFocus()`.

### Swift — the open-detour banner

New `macos/DayView/OpenDetourBanner.swift`, mirroring Compose's `OpenDetourPanel`: an amber
section header with an "open" status marker, the motif, the description when non-blank, the
elapsed clock rendered large, and a Stop button calling `stopOpenDetour()`. Styled with the
existing `dayViewPanel` modifier.

Shown in both windows when `detourOpenRunning`, **in place of** the focus panel. The two are
mutually exclusive in the core (`startOpenDetour` clears `breakStart`; a closure that hands off
to a detour opens no break), and Compose renders them the same either/or way.

### Data flow

```
Stop (main / mini / resume ritual)
  -> FocusClosureSheet
       intention field + three outcomes
       outcome costs a name?  (snapshot.earlyExitCostsName, COMPLETED always free)
         no  -> closeFocus(outcome, intention, "", "")            -> break opens
         yes -> unfold capture -> Confirm
                 -> closeFocus(outcome, intention, motif, detail) -> record + open detour, no break
       toll lifts mid-capture -> collapse to the outcome row
  -> detourOpenRunning -> OpenDetourBanner replaces the focus panel
       Stop -> stopOpenDetour() -> episode committed
```

## Known edge, deliberately not handled

`stopOpenDetour(category)` refuses a blank motif and leaves the detour open. An open detour with
no motif cannot exist natively today: only the exit toll opens one and it always names it, sync
is not yet ported, and the two apps use different preference files
(`~/Library/Application Support/DayView/` vs `~/.dayview/`). Building a stop-time motif prompt
now would be speculative. **When sync lands, it becomes reachable** — a motif-less detour started
on Android or the JVM app would sync over and the native Stop would silently do nothing. The sync
phase must add the stop-time motif collection; recorded on the parity checklist.

## Testing / done criteria

- **`:core:jvmTest`** (extending `TodaySnapshotTest` and `DayViewSessionTest`):
  - `earlyExitCostsName` is true mid-session, false at and past the term, and false while an
    open detour runs;
  - the open-detour snapshot fields render the motif, description and `MM:SS` clock, and are
    empty/false with none running;
  - an early `PROGRESSED` through `closeFocus` **with** a motif writes the session record and
    leaves an open detour running with that motif;
  - an early `PROGRESSED` **without** a motif changes nothing (the controller's silent refusal,
    asserted so the Swift-side disabled Confirm has a tested counterpart);
  - an early `COMPLETED` closes free, no detour;
  - `stopOpenDetour()` commits the episode and clears the open detour.
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test, the
  only end-to-end check of the Swift half:
  1. Start a focus, press Stop well before the term → the sheet opens.
  2. Completed → closes immediately.
  3. Start again, Stop, Progressed → the capture unfolds; Confirm is disabled until a motif is
     typed; confirming closes the session **and** the open-detour banner appears with a running
     clock.
  4. The banner's Stop commits the detour; it appears in the day's detour tally.
  5. Repeat in the mini window.
  6. Start a short session, Stop, tap Progressed, and wait for the term to pass while the capture
     is open → it collapses back to the outcome row.

## Roadmap after this phase (context only)

12b (free entry, 5-min preset, `OVERTIME` display, resume-ritual overtime copy), then per the
parity checklist: the drift notification banner and menu-bar-only ritual surfacing, the
focus-session detail popup, must-dos, hero quotes, keyboard shortcuts, sounds, history archiving,
day-over/upcoming, sync, i18n, and the packaging/CI cutover.
