# Usage contribution — external apps report focused-work time

## Goal

Let external applications (Draftline first) report *focused-work time* to DayView so
the day view can show time actually spent working toward a goal, distinct from the
existing subtractive "net time" (calendar busy intervals).

This is a **new, additive dimension**: focused time is shown on top of the day, it does
**not** reduce remaining or net time. Busy time answers "how much of my day is eaten by
meetings"; focus time answers "how much of my day did I spend well".

## Scope (v1)

- **Desktop only.** Android ships a `Noop` implementation; nothing is lost, nothing is
  shown there yet.
- Toggle-gated in settings, mirroring the net-time toggle.
- Reads *today's* events on the existing refresh cycle (pull model, same as
  `CalendarSource`).
- No new always-on process, no network, no port.

Out of scope for v1 (see Roadmap): goal-progress events, the scrobbler server, Android
transport, cross-device aggregation.

## Architecture

The feature reuses the pattern established by the net-time feature (`CalendarSource` →
busy intervals → merge/clip → arcs on the ring). It introduces a parallel *source* for
positive time.

### 1. Event log (wire format)

Each contributing app owns a **directory** and appends one JSON object per line (JSONL)
to a per-day file inside it:

```
~/.local/share/dayview/usage/<source>/YYYY-MM-DD.jsonl
   e.g.  ~/.local/share/dayview/usage/draftline/2026-07-11.jsonl
```

A directory per app means every app writes only its own files — no cross-app write
contention and no torn/interleaved appends from two apps hitting one file. Keeping the
per-day split inside each app's directory preserves a cheap "read today"
(`usage/*/YYYY-MM-DD.jsonl`) and trivial pruning (drop old dated files). Files are
append-only and never rewritten by DayView. The app is responsible for creating its own
directory.

```jsonc
{ "v": 1, "source": "draftline", "kind": "focus",
  "startedAt": 1752256800000, "endedAt": 1752260400000,
  "label": "Act II rewrite" }
```

Field semantics:

- `v` (int) — format version. Guards a future breaking change; readers reject unknown
  major versions gracefully (skip the line).
- `source` (string) — reporting app id, e.g. `"draftline"`. Free-form; used for
  attribution/labels, not logic.
- `kind` (string) — **the forward-compatibility hinge.** `"focus"` in v1. DayView renders
  the kinds it understands and **silently ignores unknown kinds**, so an app can begin
  emitting `"goal_progress"` later without breaking older DayView builds.
- `startedAt` / `endedAt` (epoch millis) — the session bounds. `endedAt > startedAt`;
  malformed or zero-length lines are skipped.
- `label` (string, optional) — human label for the hover overlay.

The schema is documented in DayView's repo (README section) so any app, not just
Draftline, can contribute.

### 2. Contract — `UsageSource`

Mirrors `CalendarSource` (`expect fun createCalendarSource()`), so transport is
abstracted from the model:

```kotlin
data class FocusInterval(
    val startMillis: Long,
    val endMillis: Long,
    val labels: List<String> = emptyList(),
)

interface UsageSource {
    fun isSupported(): Boolean
    fun focusIntervals(
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): List<FocusInterval>
}

object NoopUsageSource : UsageSource {
    override fun isSupported() = false
    override fun focusIntervals(
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): List<FocusInterval> = emptyList()
}

expect fun createUsageSource(): UsageSource
```

Implementations:

- **Desktop**: `JsonlUsageSource` — globs today's per-app files
  (`usage/*/YYYY-MM-DD.jsonl`), parses lines, filters to `kind == "focus"`, maps to
  `FocusInterval`. Tolerant parser: skips malformed lines, unknown kinds, unknown
  versions. A missing `usage/` directory yields an empty list.
- **Android**: `NoopUsageSource`.

`FocusInterval` is intentionally shaped like `BusyInterval` (`startMillis`, `endMillis`,
`labels`) so it flows through the *same* merge/clip/angle helpers in `CalendarNetTime.kt`.

### 3. Model — focus aggregation

- Merge overlapping focus intervals (reuse the merge logic from `mergeBusyIntervals`;
  extract a small generic helper or duplicate the tiny function for `FocusInterval`).
- Clip to the day window `[windowStartMillis, windowEndMillis]`.
- Sum to a `focusedTodayMillis` total.

This is purely additive to the existing `NetTime` computation. It does **not** subtract
from `netRemainingMillis` or `netDayMillis`.

### 4. Rendering

- Focus intervals become arcs via the same `busyArcs` angle math, drawn with a
  **positive accent** (filled/colored) — visually distinct from the gray busy arcs so
  "unavailable" and "productive" don't read the same.
- Secondary readout beside net time: **"Focused today: 2 h 40"** via `formatDurationHm`.
- Hover overlay reuses `angleToMillis` / `arcContainsAngle` to surface the interval
  `label`.
- Entire feature gated behind a settings toggle, exactly like net time.

### 5. Draftline write path

Draftline appends a `focus` event when a work session ends (session close in the MCP
flow, or a small explicit "log focus" step). Pure file append — no dependency on DayView
running, no coupling beyond the documented schema.

## Data flow

```
Draftline session ends
  └─ append {kind:"focus", ...} to ~/.local/share/dayview/usage/draftline/2026-07-11.jsonl
                                             │
DayView refresh cycle ─ createUsageSource().focusIntervals(window)
  └─ JsonlUsageSource globs usage/*/2026-07-11.jsonl, parses, filters kind=="focus"
       └─ merge + clip + sum ─ focusedTodayMillis
            └─ focus arcs (accent) + "Focused today" readout + hover label
```

## Error handling

- Missing `usage/` directory, no per-app files, or empty file → empty list (feature
  simply shows nothing).
- Malformed line, unknown `kind`, unknown `v` → skip that line, keep going.
- Zero-length or inverted interval (`endedAt <= startedAt`) → skip.
- Intervals partly outside the window → clipped, same as busy intervals.
- Reader is read-only; it never writes or truncates the log.

## Testing

- Parser: valid line, malformed JSON, unknown kind, unknown version, zero-length /
  inverted interval, missing file, empty file.
- Aggregation: overlap merge, clipping to window, sum correctness (reuse net-time test
  style in `CalendarNetTimeTest.kt`).
- Arc projection: focus intervals map to expected angles/sweeps (parallel to busy-arc
  tests).
- `nextIncluded`-style settings behavior if the toggle needs persisted state
  (`DayPreferences`).

## Roadmap (documented, not built in v1)

1. **Goal progress** — new `kind: "goal_progress"` events (milestone / count / percent)
   plus a rendering for them. The `kind` field and tolerant reader already accommodate
   this without a format break.
2. **Scrobbler server** — a private endpoint apps POST activity to (Last.fm-style for app
   activity), plus an `HttpUsageSource` implementing the same `UsageSource` contract.
   Unlocks cross-device and cross-app aggregation, and the Android transport the local
   file cannot cleanly provide. No model changes — it is a second implementation of the
   existing interface.
