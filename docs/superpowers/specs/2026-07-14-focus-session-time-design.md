# Focus session time (engaged time) — design

## Problem

Today DayView reports a single "focus time" figure (`focusedToday`), derived from
`focusPresenceIntervals`: the sum of foreground time spent on an app the user explicitly
marked on-goal, with off-goal excursions under 30 s bridged and runs under 120 s discarded
(`PresenceAccumulator`, `focusedTime`).

This is a purely behavioural, *strict* measure. It **underestimates** real focus: an app the
user forgot to add to the on-goal list, reading in an unlisted app, a phone call, or thinking
in front of the timer all count as zero. The user wants a complementary figure that captures
time genuinely *engaged* in the session — without swinging to the opposite failure of counting
time the machine was asleep or the user had clearly drifted.

## Goal

Report **two** figures side by side:

| | Strict (existing) | Lenient (new) |
| --- | --- | --- |
| Meaning | Proven quality focus | Time actually engaged |
| Counts | Foreground on an on-goal app | Session active, minus drift and minus interruptions |
| Source | `focusPresenceIntervals` | `focusSessionIntervals` (new) |

The gap between the two is itself informative: how well the engaged time stayed on target.

Both figures are historised (day + week), following exactly the persistence path of the
existing strict figure. Neither figure is added to the live sync document (see Non-goals).

## Non-goals

- **No sync changes.** `focusPresenceIntervals` is desktop-local: persisted via
  `DesktopPreferences` and carried into history through `DayHistoryCodec`, but it is *not* part
  of `SyncDocument` (only the `cleanSessions` ledger syncs). The new `focusSessionIntervals`
  mirrors this exactly — desktop-local, historised, not synced.
- **No second ring.** The ring keeps rendering the strict arcs only. The lenient figure is a
  number, not an arc layer, to avoid overloading the ring.
- **No churn-based cutting** in v1 (see Decisions).
- **No pomodoro-timer change.** The countdown (`pomodoroEnd`) stays pure wall-clock. This work
  only concerns the *measured* figures, not the timer.

## Design

### 1. Generalised presence accumulator

Rather than duplicate `PresenceAccumulator`, generalise it and instantiate it twice. Both the
strict and lenient accumulators are fully described by four parameters:

| Parameter | Strict | Lenient |
| --- | --- | --- |
| `presentStates` (advance the open interval) | `{ON_GOAL}` | `{ON_GOAL, NEUTRAL}` |
| `bridge` (tolerated gap before an interval closes) | 30 s | 120 s |
| `interruptionGap` (unobserved tick gap that closes an interval) | disabled | 15 s |
| `minInterval` (runs shorter than this are discarded) | 120 s | 60 s |

A tick's state is a `presentState` if it is in `presentStates`; otherwise it is a *tolerated*
state. For strict, `{OFF_GOAL, NEUTRAL}` are tolerated; for lenient, only `{OFF_GOAL}` is.

**"Close the interval" is defined once:** commit `[openStart, lastPresent]` to the closed list
*iff* `lastPresent - openStart >= minInterval`, then clear the open run (`openStart = null`).
Runs below `minInterval` are dropped, never committed. Every "close" below uses this rule.

**Per-tick algorithm** (`observe(now, state, dayKey)`), applied in order:

1. **Day rollover** — if `dayKey` changed, clear all state (unchanged from today).
2. **Interruption** — if `interruptionGap` is enabled and `now - lastObserved >= interruptionGap`,
   the span since the previous tick was unobserved (sleep / backgrounding). Close the open
   interval at `lastPresent` (not `now`), then clear the open run. This is what prevents the
   120 s lenient bridge from swallowing a 60 s sleep.
3. **Classify the current tick:**
   - `state in presentStates` → open the interval if none is open (`openStart = now`), and set
     `lastPresent = now`.
   - otherwise (tolerated) → if an interval is open and `now - lastPresent >= bridge`, close it
     at `lastPresent` and clear the open run.
4. Set `lastObserved = now`. Return the snapshot (closed runs plus the open run, iff the open
   run already meets `minInterval`).

`endSession()` and `restore(...)` keep their current contract: `endSession()` commits the open
run (if it meets `minInterval`) at a session boundary so the next session starts fresh;
`restore(...)` seeds closed intervals at startup.

**Non-regression:** with `interruptionGap` disabled and `presentStates = {ON_GOAL}`, `bridge = 30 s`,
`minInterval = 120 s`, the generalised accumulator must reproduce the current
`PresenceAccumulator` behaviour exactly. Strict does not need the interruption rule — its 30 s
bridge already closes at `lastOnGoal` after any sleep — so it stays disabled there to guarantee
byte-for-byte parity with existing tests.

The interval end always sits at `lastPresent` (the last present tick), so tolerated/gap time is
never included; only *interior* tolerated gaps shorter than `bridge` are counted, because a
later present tick extends the same run over them.

What lenient therefore counts: on-goal time + neutral time (timer / thinking) + brief off-goal
excursions under 120 s. What it excludes: off-goal sustained ≥ 120 s (drift) and unobserved
machine-away gaps ≥ 15 s.

### 2. Wiring in the desktop loop

`composeApp/src/desktopMain/.../Main.kt` runs the per-second loop that already feeds the strict
`presenceAccumulator`. Add a second `sessionAccumulator` instance (lenient parameters) fed from
the *same* `classification` each tick:

```
val updatedSession = when {
    focusIsActive -> sessionAccumulator.observe(currentNow, classification, dayKey)
    wasFocusActive -> sessionAccumulator.endSession()
    else -> focusSessionIntervals
}
```

mirroring the existing strict block (`Main.kt` ~line 196). Persist on the same cadence and
structural-change trigger as strict.

### 3. Persistence (desktop-local)

`DesktopPreferences` gains a twin of the presence pair:

- `saveFocusSession(dayKey, intervals)` / `loadFocusSession(): Pair<Long, List<FocusPresenceInterval>>`
- New preference keys `KEY_FOCUS_SESSION_DAY` / `KEY_FOCUS_SESSION`, encoded/decoded with the
  existing `encodeFocusPresence` / `decodeFocusPresence` (same `FocusPresenceInterval` shape).

Seed it into the initial state at startup exactly like `initialFocusPresenceIntervals`.

### 4. Controller & state

`DayViewUiState` gains `focusSessionIntervals: List<FocusPresenceInterval> = emptyList()` and a
derived:

```
val sessionFocusedToday: Duration
    get() = focusedTime(windowStart, windowEnd, focusSessionIntervals)
```

reusing the existing `focusedTime(...)` unchanged. Add `setFocusSessionIntervals(...)` alongside
`setFocusPresenceIntervals(...)`, wired through `App.kt` the same way (a `LaunchedEffect` on the
new list plus an `initialFocusSessionIntervals` seed parameter).

### 5. History

`DayHistoryRecord` gains `focusSessionIntervals`, clipped to the day window in `toHistoryRecord`
identically to `focusPresenceIntervals`, and restored in `toFrozenUiState`. `DayHistoryCodec`
adds a `session=` line (encoded like `presence=`), read back with a default of empty list so
existing records without the line still decode.

### 6. UI

The today screen and history day/week screens already read `state.focusedToday`. Surface
`state.sessionFocusedToday` next to it. Concretely, the readout that currently shows the
`focused_today` string (`DayViewTodayScreen.kt` ~line 1150, and `HistoryDayScreen`) gains a
sibling line for the engaged figure. New string resources (en + fr). Default labels: strict =
"Deep focus" / "Focus profond", lenient = "Engaged" / "Temps engagé" — the maintainer may
reword during implementation. Reuse `formatDurationHm`.

## Decisions

- **Churn (rapid app-switching) is not a cutting cause in v1.** The `FocusDriftDetector` churn
  heuristic (≥ 4 switches in 45 s) carries sliding-window + cooldown state that does not belong
  in a stateless-per-tick accumulator. Sustained-off-goal + interruption already exclude the
  bulk of genuine drift. During churn the lenient figure counts the active time (the user is at
  least engaged/active); the strict figure will show the quality drop. Revisit as a future knob
  if churn-heavy sessions prove to inflate the lenient figure misleadingly.
- **Lenient `bridge` = 120 s**, aligned with `FocusDriftDetector.sustainedOffGoal`, so "counts as
  focus" and "does not fire a drift nudge" mean the same thing.
- **Lenient `minInterval` = 60 s** (vs strict 120 s): engaged runs can be shorter and more
  fragmented than proven-focus runs, so a lower floor avoids dropping real engaged time while
  still filtering single-tick noise.
- **Lenient `interruptionGap` = 15 s**, reusing the `FocusResumeDetector` interruption threshold,
  so the two notions of "the app stopped observing" stay consistent.

## Tunable knobs (not open questions — defaults chosen above)

`bridge`, `minInterval`, and `interruptionGap` are constructor parameters, so the lenient
thresholds can be adjusted after real-world use without structural change.

## Testing

- **Non-regression:** the existing `PresenceAccumulator` cases must stay green against the
  generalised accumulator configured with strict parameters.
- **Lenient truth table** (new): neutral time counted; off-goal < 120 s counted; off-goal ≥ 120 s
  closes the run at drift onset; a 60 s unobserved gap (no ticks) excluded via the 15 s
  interruption rule; run < 60 s discarded.
- **Codec round-trip** with the new `session=` line, plus a legacy record lacking the line
  decoding to an empty list.
- **History re-derivation:** a frozen record yields the expected `sessionFocusedToday`.
- ktlint + `:composeApp:testDebugUnitTest` + `:composeApp:desktopTest` green.
