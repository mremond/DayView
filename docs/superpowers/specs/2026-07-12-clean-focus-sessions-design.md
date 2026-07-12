# Clean focus sessions — a low-stakes reward for showing up

## Context

DayView's long-term goal for its owner is **writing**, and the owner is in a **revision
phase** (cutting and re-reading), so word-count-style volume metrics are actively wrong:
the best work *reduces* the word count. Two motivational failures were identified during
brainstorming: writing does not "pay" fast enough compared to programming, and building
one's own tools (including DayView itself) is a noble-feeling escape from writing
(meta-procrastination). A further, decisive constraint surfaced: the owner is *afraid to
even click the focus button*, so the feature must lower the activation energy to start a
session, never raise it.

The reward signal chosen is **the number of "serious" focus sessions completed per day** —
sessions run to term with no distraction — plus a **daily streak** on top. This rewards
quality of presence, which is exactly what a revision phase needs, and survives the
cutting work.

Almost all the machinery already exists:

- [`Pomodoro.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/Pomodoro.kt)
  gives the session window `[end − duration, end]` via `calculatePomodoroProgress`, and
  `FocusClosureOutcome` (`COMPLETED` / `PROGRESSED` / `TO_RESUME`) already classifies how
  a session closed.
- [`PresenceAccumulator.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/PresenceAccumulator.kt)
  and [`OnGoalState.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/OnGoalState.kt)
  already classify the frontmost app per tick as `ON_GOAL` / `OFF_GOAL` / `NEUTRAL`.
- [`Detours.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt)
  already provides dated distraction episodes and the `dayKeyOf` day-scope convention.

## Goal

Count each day's **clean focus sessions** and maintain a **daily streak**, then surface
both as a glanceable, purely-positive display on the Today screen. Nothing punishes a
session that fails the criteria — starting a session is pure upside, so clicking the
button can never feel like a risk.

## Validated decisions

- **Clean session = all three true:**
  - Closed as `COMPLETED` only. `PROGRESSED` and `TO_RESUME` do **not** count — "serious"
    means the timer ran to the end.
  - Off-goal drift within the session window ≤ a small tolerance (default **30 s**,
    matching `PresenceAccumulator`'s `bridge`). `NEUTRAL` (DayView itself, blank screen)
    is **not** a distraction.
  - No `DetourEpisode` overlaps the session window `[start, end]`.
- **Streak kept.** A day counts toward the streak if it has **≥ 1** clean session
  (threshold fixed at 1). Consecutive qualifying days extend it; a day without a clean
  session breaks it at the next rollover.
- **Only-positive UI.** A non-clean, abandoned, or distracted session leaves **no negative
  trace** — no red pip, no failure counter, no alert. This is the core design stance
  against the fear of clicking Start.
- **No settings in v1.** Tolerance (30 s) and streak threshold (1) are hard-coded
  defaults; a settings surface can come later only if a real need appears (YAGNI).
- **Purely informational.** Clean-session counting never affects the countdown, net time,
  detours, or the Pomodoro timer itself.

## Data model and persistence

All logic lives in pure functions and a small tracker, testable without UI, following the
existing patterns.

**In-session tracking** — a new `SessionCleanlinessTracker` accumulates, while a session
is `ACTIVE`, the wall-clock time observed in `OFF_GOAL` state within the current session
window. It is fed from the same per-tick loop that already drives `PresenceAccumulator`.
`NEUTRAL` and `ON_GOAL` ticks add nothing. It resets when a new session starts.

**Evaluation** — a pure function:

```kotlin
fun evaluateSessionClean(
    window: FocusSessionWindow,      // start, end (= start + duration)
    offGoalDuring: Duration,          // accumulated OFF_GOAL time in the window
    detours: List<DetourEpisode>,     // today's declared detours
    outcome: FocusClosureOutcome,
    tolerance: Duration = 30.seconds,
): Boolean
```

returns true iff `outcome == COMPLETED`, `offGoalDuring <= tolerance`, and no detour
overlaps the window.

**Daily ledger + streak** — a small serializable state, day-scoped like detours:

```kotlin
data class CleanSessionLedger(
    val dayKey: Long,          // dayKeyOf(now); resets count on rollover
    val cleanToday: Int,
    val streakDays: Int,
    val streakLastDayKey: Long, // last day that reached the threshold
)
```

- On rollover (`dayKeyOf(now) != dayKey`): `cleanToday` resets to 0; the streak is left
  intact until re-evaluated, and is broken lazily — if the first clean session of a new
  day finds `streakLastDayKey < dayKey - 1`, the streak restarts at 1.
- Registering a clean session: `cleanToday++`; if this is the day's first clean session,
  update the streak (`streakLastDayKey == dayKey - 1` ⇒ `streakDays++`, else
  `streakDays = 1`) and set `streakLastDayKey = dayKey`.
- A pure `registerCleanSession(ledger, dayKey): CleanSessionLedger` and a
  `rollOver(ledger, dayKey): CleanSessionLedger` keep the transitions unit-testable.
- **Display is honest, not stale:** a pure `displayedStreak(ledger, dayKey): Int` returns
  `streakDays` only while the streak is still alive (`streakLastDayKey >= dayKey - 1`) and
  `0` otherwise. So a streak that is already dead (a day was missed and today has no clean
  session yet) never shows as intact; it reappears the moment today's first clean session
  restarts it at 1. The UI always reads through this function, never `streakDays` raw.
- Persisted in `DayPreferences` via `DayPreferencesStore` as four individual typed
  preference keys (following the `SoundSettings` and `detoursDayKey` precedent), not a
  string-encoded blob. Absent keys read back as the empty default ledger, so a fresh
  install and a stale-day install both start clean without a parser or a malformed-input
  class.

## Wiring

Off-goal drift detection is **desktop-only**: the per-tick frontmost-app classification
lives in the desktop loop (`Main.kt`), which already feeds `PresenceAccumulator`. Android
has no frontmost-app classification, so its `offGoalDuring` is always `Duration.ZERO` and a
session there is clean when `COMPLETED` with no overlapping detour. The design degrades
gracefully — the streak and counter work on both platforms; the anti-distraction criterion
is a desktop refinement.

- On the desktop loop, a `SessionCleanlinessTracker` accumulates `OFF_GOAL` time for the
  current session (keyed by the active `pomodoroEnd`; resets when a new session starts),
  in the same tick branch that already calls `presenceAccumulator.observe`. Its running
  total is bridged into `DayViewApp` exactly like `focusPresenceIntervals`, and fed to the
  controller via `setSessionOffGoal(Duration)`.
- On session **close** with its `FocusClosureOutcome`: `evaluateSessionClean` runs in
  `closePomodoro` using the window `[pomodoroEnd − duration, pomodoroEnd]`, the mirrored
  `sessionOffGoal`, and today's detours; if clean, `registerCleanSession` updates and
  persists the ledger. This rides on the existing `closePomodoro(outcome)` hook.
- On **day rollover** (same `dayKeyOf` trigger the rest of the state uses): `cleanToday`
  resets via `rollOver` when a session closes on a new day.

## UI (Today screen)

A discreet line beneath the dial/countdown, valuing the positive only:

- Filled pips for each clean session today (e.g. `● ● ● ○`), plus a localized
  "N sessions sérieuses" / "N serious sessions" label.
- The streak shown next to it when `streakDays >= 1` (e.g. "série 5 j" / "5-day streak").
- When there are zero clean sessions yet and no streak, the line is minimal or absent — no
  empty-state scolding.
- Rendering reads the persisted ledger; no new observation surface. All strings come from
  Compose resources (fr + default), so no `stringResource` is asserted in UI tests.

## Out of scope for v1

Settings for tolerance / streak threshold; counting `PROGRESSED` sessions; any "just 5
minutes" quick-start nudge; multi-day history or weekly stats; mini-window / menu-bar /
Android-widget surfaces; any punitive or failure-tracking display; Draftline integration
or scene-milestone rewards.

## Testing

- `evaluateSessionClean`: COMPLETED vs PROGRESSED vs TO_RESUME; off-goal exactly at / just
  over tolerance; detour overlapping vs adjacent (touching an edge) vs outside the window;
  empty detour list.
- `SessionCleanlinessTracker`: accumulates only OFF_GOAL, ignores NEUTRAL/ON_GOAL, resets
  on new session.
- Ledger transitions: `registerCleanSession` increments count and updates streak only on
  the day's first clean session; `rollOver` resets count; streak continues across
  consecutive days, restarts after a gap.
- `displayedStreak`: shows `streakDays` on the day of / day after the last qualifying day,
  returns 0 once a day has been missed with no clean session yet today.
- Ledger persistence round-trip through `DayPreferencesSnapshot`; absent keys restore the
  empty default ledger.
- Platform test suites follow the existing split (`testDebugUnitTest` + `desktopTest`);
  pure logic only, no composables under test.
