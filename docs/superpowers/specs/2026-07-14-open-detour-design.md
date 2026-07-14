# Open Detour (unknown-duration detour) — Design

## Problem

DayView models two kinds of timed activity:

- **Focus / pomodoro** — fixed duration, live countdown, states IDLE / ACTIVE /
  BREAK, never paused. The running session is a single field `pomodoroEnd:
  Instant?`.
- **Detour (`DetourEpisode`)** — a diversion logged *after the fact*, with concrete
  `start` and `end`. There is no such thing as a "running" detour today.

Missing case: starting a detour whose duration is **not known in advance** — a
stopwatch that counts up with no limit and is ended by an explicit stop, no pause.
Conceptually "a pomodoro without a limit".

## Concept

A third mode of timed activity: an **open detour** that counts up from its start
instant until the user stops it. On stop it is converted into an ordinary
`DetourEpisode(start, end = now, category, description)` via the existing capture
path — so all downstream machinery (ring projection, history archival, episode
editing, serialization) is reused unchanged.

**Open detour and focus are mutually exclusive.** Starting one is refused while the
other is active, in both directions.

## State model

Mirror the proven `pomodoroEnd` pattern. Add to both `DayViewUiState`
(`core/.../DayViewController.kt`) and its persisted twin `DayPreferencesSnapshot`
(`core/.../DayPreferences.kt`):

- `openDetourStart: Instant?` — `null` = no open detour; an instant = an open detour
  running since that moment. Single source of truth, exactly like `pomodoroEnd`.
- `openDetourCategory: String` — category entered at start, held until stop.
- `openDetourDescription: String` — description entered at start, held until stop.

No new enum: the running/idle distinction is derived from `openDetourStart == null`,
the same way pomodoro state is derived from `pomodoroEnd`.

Rejected alternative: a dedicated mutable "running Detour" object. It would duplicate
the `pomodoroEnd` mechanics already exercised across persistence, sync, restart and
tests, for no gain.

## Controller actions (`DayViewController`)

Symmetric to `startPomodoro` / `stopPomodoro`:

- `startOpenDetour(category, description)` — refused if a focus session is ACTIVE
  (exclusivity). Sets `openDetourStart = now`, stores category/description, persists.
- `stopOpenDetour()` — routes through the existing `addDetourEpisode(start =
  openDetourStart, end = now, category, description)`, then clears
  `openDetourStart`/category/description back to null/empty. Duration passes through
  the existing detour sanitizer (clamped to the current 1..12 h bound), so an open
  detour left running very long is bounded the same way a manually entered one is.
- `startPomodoro()` — additionally refused while `openDetourStart != null` (the other
  direction of exclusivity).

## Elapsed-time computation

Reuse the existing count-up mechanic from the pomodoro BREAK phase: `elapsed = now -
openDetourStart`, formatted by the existing `formatBreakClock`. Expose a small
`openDetourElapsed` derivation on the state, recomputed each tick. The 1 s ticker in
`App.kt` already drives recomposition and is reused as-is (no new timer).

## UI

Entry point (`DetoursUi.kt` — `DetourCaptureDialog`):

- Add a **"Démarrer"** button next to the existing **"Ajouter"** (retroactive-log)
  button. Category and description are entered as usual; "Démarrer" ignores the
  duration stepper and starts the open detour via `startOpenDetour`.

Running display (`DayViewTodayScreen.kt` — `FocusPanel`):

- When `openDetourStart != null`, render a branch modelled on the BREAK branch: the
  detour label (category / description), the count-up clock, and a single
  **"Arrêter"** button wired to `stopOpenDetour`.
- The focus creation/start controls are hidden or disabled while an open detour runs
  (exclusivity), and vice-versa.

## Persistence

New DataStore keys in `DayPreferenceKeys` (`core/.../DayPreferencesStore.kt`),
following `pomodoro_end` exactly:

- `detour_start` — epoch-millis, sentinel `-1` for null.
- `detour_open_category` — string.
- `detour_open_description` — string.

Thread the three fields through `toSnapshot` / `toUiState` / `withPersisted` /
`coerced` (`DayViewController.kt`) and through the sync mappers
(`composeApp/.../sync/SyncMapper.kt`), given the same treatment as `pomodoroEnd` so
the open-detour state propagates across devices. On restart, an open detour resumes
counting up from the persisted start instant.

## Out of scope (assumed defaults)

- **No Android alarm.** An open detour has no end, so there is nothing to schedule —
  simpler than focus. `onFocusAlarmChange` is untouched.
- **Ring appearance only on stop.** The detour appears on the ring once it becomes a
  `DetourEpisode` (i.e. after stop), not while running.
- **Android tile / notification / widget** deal only with focus and ignore the open
  detour for this MVP.

## Tests

- `core` — new `OpenDetourTest`: start → tick → stop creates the expected
  `DetourEpisode`; focus↔detour exclusivity refused in both directions; an open
  detour survives a persistence round-trip and resumes.
- Desktop UI — a test for the `FocusPanel` running branch (count-up + "Arrêter") and
  for the "Démarrer" button in `DetourCaptureDialog`.
