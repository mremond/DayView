# Completed-day rendering — peaceful closure with recap

## Problem

When the working day is over, the countdown ring reads as *empty / expired* rather than
*complete*. As soon as `animatedRemaining == 0`, the whole arc-drawing block in
`CountdownCircle` is skipped, so only the faint background track (`.075` alpha) and the tick
marks remain. The centre shows "JOURNÉE TERMINÉE" in **red** (an alarm colour), with no
counter and no summary. Leftover calendar and detour marks stay drawn at full intensity.

The result feels punitive. We want the finished state to feel like a calm, accomplished
**closure of the day**, with a small recap of what was done.

## Scope

All changes are confined to `CountdownCircle` in
`composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`. No new state, no
data-flow changes, no public API changes — every value needed (`focusedToday`,
`cleanSessionsToday`, `streakDays`, calendar/detour marks) is already passed into the
composable.

Explicitly **out of scope**: the red status dots in the compact/mini layouts
(`CompactTodayContent` and the mini counter). They live on surfaces that have no ring and are
left unchanged. Harmonising them can be a separate follow-up.

## Design

### 1. The ring at rest

Today the ring is only drawn inside `if (animatedRemaining > 0f)`, so nothing draws when the
day is over. Add a **finished branch** that draws the full circle:

- A complete 360° arc in `colors.mint` at a low alpha (≈ `.45`), replacing the ghost track as
  the dominant ring. Uniform colour (no sweep gradient — there is no leading edge to justify
  one, and a full ring would show a seam).
- A **resting marker** parked at the top (−90°, the day's start/end point): a small, dim mint
  dot, quieter than the live amber moment marker, signalling "the loop is closed."
- Change the animated `accent` colour for the finished state from `colors.red` to
  `colors.mint` (the existing `animateColorAsState` at the top of `CountdownCircle`).

### 2. The title

"JOURNÉE TERMINÉE" changes from `colors.red` to a soft mint tone, coherent with the ring. No
more alarm red.

### 3. The central recap

Two calm stats under the title. Both already have strings and formatting helpers.

- **Focus done** — the `focused_today` line ("Focus 3 h 20") is currently locked inside
  `if (!progress.isFinished)`, so it vanishes at end of day. Make it render in the finished
  state too (in mint), shown only when `focusedToday > Duration.ZERO`.
- **Clean sessions / streak** — the existing block (mint pips + "Serious 4 · Streak 5 d") is
  already drawn regardless of finished state; leave it as is.
- An empty day (0 focus, 0 sessions, 0 streak) shows just the title — no hollow lines.

### 4. Residual marks

Fade the leftover journey. When `progress.isFinished`, multiply the draw alpha of the calendar
busy arcs and the detour bodies by ≈ `.4`, so they read as faint traces of the day that is now
closed rather than active elements competing with the mint ring and the recap.

## Testing

Add a desktop UI test (following the project's test constraints — assert via test tags and
seeded data, never via `stringResource` text, which is unresolved under `runComposeUiTest` on
CI):

- With a finished `DayProgress` and seeded `cleanSessionsToday` / `streakDays`, the
  `CleanSessions` tag exists in the finished state.
- With a finished `DayProgress` and `focusedToday > 0`, the focus recap renders (via a tag on
  the focus recap element, added as part of this work).

## Non-goals / YAGNI

- No new metrics beyond focus + sessions/streak (detours, calendar time stay hidden in the
  finished state to keep it calm).
- No animation choreography beyond the existing colour/ratio tweens.
- No changes to the compact or mini status indicators.
