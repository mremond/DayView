# Asymmetric focus — free to enter, conscious to finish, named to flee

## Context

The owner's two real leak points, identified during brainstorming, sit at the two ends of
the Focus lifecycle:

- **Sessions never start.** The activation-energy problem named in
  [`2026-07-12-clean-focus-sessions-design.md`](2026-07-12-clean-focus-sessions-design.md)
  ("the owner is afraid to even click the focus button") was answered on the *after* side
  — nothing punishes a failed session — but the *before* side still carries a toll:
  `startPomodoro()` refuses to start while `focusIntention` is blank
  ([`DayViewController.kt`](../../../core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt)).
  A mandatory text field is exactly the toll the spec said must not exist.
- **Leaving is free.** Switching to another Mac app only triggers a passive nudge; the
  Stop button aborts the session in one tap and — a pre-existing hole — skips the closure
  ritual entirely (`stopPomodoro()` records `outcome = null`).
- **Hyperfocus is punished.** `PomodoroStatus.BREAK` is *derived*: it is simply "past the
  term, not yet closed" ([`Pomodoro.kt`](../../../core/src/commonMain/kotlin/fr/dayview/app/Pomodoro.kt)),
  and engaged time is capped at the term (`effectiveEnd = minOf(stopInstant, end)`). When
  the owner is concentrated enough to miss the chime — the best possible state — the app
  silently stops counting the work. This is the same flaw that motivated engaged time:
  the strict figure underestimates real focus.

The design principle, validated during brainstorming: the Focus lifecycle becomes
**asymmetric** — *entering is free, finishing is a choice, fleeing costs a name*. The
only-positive doctrine holds in full; no failure trace appears anywhere. Friction moves
from the start (where it deterred the fearful click) to the early exit (where it makes
the commitment self-enforcing — a Ulysses contract, not a punishment).

Explicitly kept small on purpose: the owner's own spec names tool-building as
meta-procrastination. One coherent change to the Focus lifecycle, no new subsystem.

## Goal

Make starting a Focus a zero-friction act, make leaving one before term require naming
the attractor (as a detour — existing vocabulary), and make time worked past the term
count instead of silently draining into an unnoticed break.

## Validated decisions

- **Free entry.** Start begins immediately; the intention field becomes optional. The
  intention is *invited* at closure ("what was it?"), pre-filled when one was typed,
  still kept verbatim by `TO_RESUME` (`focusIntentionAfterClosure` unchanged).
- **Quick preset.** A secondary one-tap "5 min" affordance starts a 5-minute session
  with no input at all, without touching the preferred duration.
- **Finishing is free, fleeing costs a name.** Stopping before term always goes through
  the closure ritual (closing the `outcome = null` hole). `COMPLETED` before term is
  free — done early is done, no detour demanded (no streak credit either: reaching the
  term remains the "serious" criterion). `PROGRESSED` or `TO_RESUME` before term
  requires naming the pull: the existing detour capture opens and confirming starts an
  **open detour** (the existing stopwatch). The user can lie by tapping `COMPLETED`; the
  app trusts self-declaration everywhere else and does here too.
- **Overtime counts ("Élan" / "Overtime").** The timer reaching its term is an
  *invitation*, not a closure: the chime fires, the session stays alive and counted, the
  UI shows "+N min". The break starts at closure, not at the term. Vocabulary table
  (README): FR **Élan**, EN **Overtime**.
- **One soft reminder.** When overtime reaches the planned duration (total = 2× planned),
  a single discreet notification suggests closing. Fired once per session, never
  repeated.
- **No new negative trace.** No failure counter, no red mark. The only new artifact of an
  early exit is a neutral detour body on the ring — "a detour is not a fault".
- **No settings in v1.** Preset duration (5 min), reminder threshold (2×) hard-coded
  (YAGNI).
- **Works on both platforms without new observation.** The exit toll rides the Stop
  button, not a detector. Android drift detection stays out of scope.

## State model

`PomodoroStatus` becomes `IDLE / ACTIVE / OVERTIME / BREAK`:

- `ACTIVE`: now < term. Unchanged.
- `OVERTIME`: now ≥ term, session not yet closed. This is the window formerly mislabeled
  `BREAK`. `overtimeElapsed = now − term` drives the "+N min" display.
- `BREAK`: derived — `breakStart != null` and `now − breakStart ≤ 60 min` (the existing
  `MAX_BREAK_REMINDER_MINUTES` cap); beyond the cap the status decays to `IDLE` so the
  break clock never runs unbounded. Needs a real anchor: a new `breakStart: Instant?`
  set by `closePomodoro`, cleared by `startPomodoro`. `BreakReminderScheduler` keeps its
  10-minute cadence but anchors to `breakStart` instead of the term.

The active session snapshots its own duration at start (`pomodoroSessionMinutes`
alongside `pomodoroEnd`), decoupling the session window from the `pomodoroMinutes`
preference. This is what lets the quick preset start a 5-minute session without
clobbering the preferred duration, and it removes the existing fragility where the
window is re-derived from a mutable preference.

New/changed persisted fields (individual typed keys, following the ledger precedent):
`pomodoroSessionMinutes`, `breakStart`. Both join the sync document as LWW-versioned
fields like `pomodoroEnd`.

## Lifecycle changes (controller)

- `startPomodoro(minutes = state.pomodoroMinutes)`: drop the `focusIntention.isBlank()`
  guard; snapshot `pomodoroSessionMinutes = minutes`; clear `breakStart`. The quick
  preset calls it with `minutes = 5`.
- `stopPomodoro()` (one-tap, no outcome) is **removed**. Every stop routes through the
  closure sheet. UI "Stop" opens the sheet; cancel returns to the running session.
- `closePomodoro(outcome, intention, detour?)`:
  - Applies the (possibly edited) intention to the closing record before
    `focusIntentionAfterClosure` runs.
  - Records `effectiveEnd = state.now` (no longer capped at the term) — overtime lands
    in `FocusSessionRecord` and, on Android, in `appendEngagedSession`'s derivation
    (still minus overlapping detours). Desktop per-tick presence needs no change.
  - Sets `breakStart = state.now`, clears `pomodoroEnd` / `pomodoroSessionMinutes`.
  - **Early-exit gate (pure, testable):** a
    `earlyExitRequiresDetour(now, term, outcome): Boolean` returns true iff
    `now < term && outcome != COMPLETED`. When true the controller requires a non-blank
    detour category; the closure then chains the existing sequence "close, then
    `startOpenDetour(category, description)`" (mutual exclusivity is already enforced
    in that order).
- **Cleanliness window unchanged:** `evaluateSessionClean` keeps judging
  `[start, term]`. Off-goal time or detours during overtime never spoil a serious
  session; `SessionCleanlinessTracker` stops accumulating at the term.

## Reminders and sounds

- The existing end-of-focus chime at the term is untouched — it *is* the invitation.
- New single reminder at `term + pomodoroSessionMinutes`: discreet notification, existing
  nudge channels (macOS notification; Android needs a second exact alarm scheduled next
  to the existing end-of-focus one in `FocusAlarmScheduler`, cancelled on closure).
- `BreakReminderScheduler` re-anchors to `breakStart`; its copy ("take time to
  disconnect", "resuming stays a conscious choice") now fires during an actual break,
  which is where it always belonged.

## UI

- **Focus panel:** Start is always enabled; the intention field stays, marked optional;
  a compact "5 min" secondary affordance sits beside Start.
- **Closure sheet:** gains a single-line intention field above the three outcome chips
  ("What was it?"), pre-filled; optional. When the early-exit gate demands a name, the
  sheet extends with the existing detour capture (recent-motif chips + optional
  description) and the confirm launches the open detour. Cancel resumes the session.
- **Overtime display:** "+N min" replaces the break clock in the Today panel, menu-bar
  title, mini window, Android notification and widget (`TodaySnapshot` label changes
  flow to macOS native automatically).
- **Drift nudge fallback:** with no intention typed, the nudge body falls back to the
  generic title line instead of an empty string.
- All new strings in EN + FR (`values-fr`), per the localization rule.

## Out of scope

Android drift detection; any app/site blocking; external accountability; end-of-day
review; streak visibility changes; configurable preset/reminder thresholds; counting
overtime toward the "serious" criterion; macOS-native UI parity work beyond what flows
through `TodaySnapshot` (gaps become PORT items in the parity checklist).

## Testing

- `calculatePomodoroProgress`: ACTIVE→OVERTIME at the term; `overtimeElapsed` growth;
  BREAK only with `breakStart` set; IDLE when neither.
- Session-duration snapshot: quick preset starts 5-min session, preference unchanged;
  duration change while IDLE still works; window derives from the snapshot.
- `earlyExitRequiresDetour`: before/at/after term × three outcomes.
- Controller closure: early `PROGRESSED` with category → record written, open detour
  running; early `PROGRESSED` without category → no state change; early `COMPLETED` →
  free, no detour; at-term closure unchanged; intention edited at closure lands in the
  record and survives `TO_RESUME`.
- Overtime counting: `FocusSessionRecord.end` = closure instant past the term; Android
  engaged derivation extends past the term minus detours; cleanliness still evaluates
  `[start, term]` and off-goal during overtime does not spoil it.
- Reminder: fires once at 2× planned, not again; cancelled by closure.
- `BreakReminderScheduler` anchored to `breakStart`: cadence preserved, no fire without
  a break, re-anchor on new closure.
- Persistence + sync round-trip of `pomodoroSessionMinutes` and `breakStart`; absent
  keys restore defaults.
- Platform split as usual (`:core:jvmTest`, `desktopTest`, `testDebugUnitTest`); pure
  logic only, no composables under test.
