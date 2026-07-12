# Day history & week overview — design

## Problem

Today DayView renders a single "circle" (`CountdownCircle`) that is a pure projection
of the current, live `DayViewUiState`. Day-scoped data (detours, clean sessions, focus
presence, pomodoro) is tagged with a `dayKey` and **discarded at midnight** — no prior
day is ever retained. There is no `Day` entity, no date navigation, and no persistence
of history.

We want to browse past days as small rendered rings and click one to review that day in
full. First deliverable: a **week overview** (7 clickable mini-rings) plus the history
storage layer. Month and year views come later, reusing the same building blocks.

## Decisions (agreed during brainstorming)

- **Fidelity:** faithful snapshot. A reviewed past day replays what the app actually
  captured that day — not a recomputation from the (possibly-changed) current calendar.
- **Missing days:** partial snapshot. If the app ran at all during a day, keep whatever
  was captured (even incomplete). A day the app never ran = empty, non-clickable cell.
- **Scope, first iteration:** week view only. Month/year deferred.
- **Storage:** one record per day (approach A), encoded in the existing hand-rolled
  line-based style, one file per `dayKey`, `expect`/`actual` per platform. No new
  dependency (no SQLDelight/JSON) for now.
- **What we archive:** the raw day-scoped model inputs, not the projected arcs — so
  reconstruction runs through the *same* projection code as the live ring.
- **Navigation:** extend the existing `DayViewDestination` enum; do not introduce
  `navigation-compose`.

## Architecture

Three new units in `commonMain`, plus a hook into the existing controller:

```
DayViewController (existing)
   │  on rollover (persisted dayKey != dayKeyOf(now)) → flush previous day
   ▼
DayHistoryStore (new commonMain interface; expect/actual Android + Desktop)
   │  read/write one record per dayKey
   ▼
DayHistoryRecord (new data class = day-scoped subset of state)
   │  record.toFrozenUiState() → frozen DayViewUiState → existing projection
   ▼
HistoryWeekScreen (new)  → 7 MiniRing
   └─ click → HistoryDayScreen (new; reuses CountdownCircle read-only)
```

Guiding principle: **no new drawing logic**. The week grid uses a slimmed miniature of
the existing arc projection; the drill-in reuses `CountdownCircle` unchanged. The
controller only gains one archival point and a little navigation state.

## Data model: `DayHistoryRecord`

Immutable `data class` in `commonMain`, holding exactly the day-scoped fields already
present on `DayViewUiState`:

- `dayKey: Long` — local epoch-day (as computed by `dayKeyOf`)
- `startMinutes: Int`, `endMinutes: Int` — the day window
- `intention: String?`
- `busyIntervals: List<BusyInterval>` — per-calendar, with `CalendarInfo`
- `focusPresence: List<FocusPresenceInterval>`
- `detours: List<DetourEpisode>`
- `cleanSessions: CleanSessionLedger`
- `netTimeSettings: NetTimeSettings`
- `pomodoro: PomodoroProgress` (+ `PomodoroStatus`)
- `globalGoal: GlobalGoal?`

`record.toFrozenUiState(): DayViewUiState` is a pure function producing a
`DayViewUiState` with `now` frozen at the end of the day window, so the existing
computed projections (`busyBlockArcsState`, `focusArcsState`, `detourBodiesState`,
`dayProgress`, goal glow, pomodoro) render identically to a live day.

### Encoding: `DayHistoryCodec`

Reuse the existing line-based style (`encodeDetours`, `encodeFocusPresence`, …),
grouped in one codec, with a **format version header** as the first line. Decoding is
defensive: an unknown version or a malformed file is logged and treated as absent
(`null`) — never a crash.

## Storage: `DayHistoryStore`

`commonMain` interface, one record per day:

```kotlin
interface DayHistoryStore {
    suspend fun write(record: DayHistoryRecord)
    suspend fun read(dayKey: Long): DayHistoryRecord?      // null = empty cell
    suspend fun listDays(range: LongRange): List<Long>     // dayKeys present in window
}
```

- `expect`/`actual` mirroring `DayPreferences`: Android under `filesDir/history/`,
  Desktop under `~/.dayview/history/`.
- One file per `dayKey`, named by the key. `listDays` enumerates the directory filtered
  by the requested range (week = 7 keys). No separate summary index yet — added later
  for month/year.
- Defensive reads: corrupt/unreadable/unknown-version file → logged, treated as `null`.
- Atomic writes: write to `<dayKey>.tmp`, then rename, so a kill mid-flush never leaves a
  half-written record.

## Capture / archival

- **Anchor:** the rollover already detected in `DayViewController` (persisted `dayKey`
  != `dayKeyOf(now)`). Before resetting the day's data, build a `DayHistoryRecord` from
  the persisted *previous-day* state and call `store.write(...)`.
- This covers the **partial snapshot** case: if the app ran then closed before midnight,
  the partial state is already on disk and gets archived at the next launch when
  rollover is observed. A day with no run at all = no file = empty cell.
- **Today is not archived** (it is live): the grid reads the current day from the live
  controller, past days from the store.
- **Idempotence:** if a `dayKey` already has a file, the flush does not overwrite it —
  avoids clobbering an earlier archive with residual state.

## Rendering

- **`MiniRing`** — a lightweight Canvas composable drawing the same arc structure (track,
  busy, focus) at small size, without center text or hover interactions. It consumes the
  projection from `toFrozenUiState()`. Shared geometry with `CountdownCircle` is factored
  out where natural, without changing the existing ring's output.
- **`HistoryWeekScreen`** — a grid of 7 `MiniRing` (Mon→Sun), each labelled with its
  date. Days with no data are greyed and non-clickable. The current day may be
  highlighted.
- **`HistoryDayScreen`** — reuses `CountdownCircle` read-only, fed by the selected day's
  `toFrozenUiState()` — same arcs, goal glow, pomodoro. No action panels (no "start
  focus" on a past day).

## Navigation

- `DayViewDestination` gains `HISTORY`; the controller gains `selectedHistoryDay: Long?`.
- `null` → `HistoryWeekScreen`; non-null → `HistoryDayScreen`. Back
  (`PlatformBackHandler`): day → week → Today.
- Entry point from the Today screen: a **history icon** button. Week starts Monday.
  History is retained indefinitely (one small file per day).

## Error handling

- Corrupt / unknown-version record → treated as an empty (non-clickable) cell, logged.
- Store I/O failures on read → empty cell; on write → logged, non-fatal (the day is
  simply not archived; a future faithful archive of that day is no longer possible, which
  is acceptable).
- Atomic write (tmp + rename) prevents half-written records.

## Testing

Per project constraints (no assertions on `stringResource`; test pure screens with
seeded data; `assertExists` is a member):

- **Unit:** `DayHistoryCodec` round-trip (encode→decode; unknown version → `null`);
  `toFrozenUiState()` yields the expected arcs; `listDays` filters the window correctly.
- **Store:** write/read/list against a temp directory; corrupt file → `null`; atomic
  write leaves no `.tmp` behind on success.
- **Rollover:** a changing `dayKey` triggers exactly one `write`, and is idempotent when
  a record already exists.
- **UI:** `HistoryWeekScreen` with 7 seeded days (including gaps) shows clickable vs
  greyed via test tags; `HistoryDayScreen` renders the ring without crashing.

## Out of scope (deferred)

- Month and year overviews (reuse `MiniRing` + a summary index for large ranges).
- A lightweight per-day summary index for cheap large-range rendering.
- Backfilling missing days from the current calendar (rejected: breaks fidelity).
- Editing or annotating past days.

## Notes

- Feature targets **Android + Compose Desktop** (both via `commonMain`). The repo has no
  native SwiftUI UI target on this branch (only `androidTarget()` + `jvm("desktop")`);
  the `scripts/*.swift` files are subprocess helpers, not a UI layer.
