# Calendar busy blocks — hybrid, per-calendar-colored objects on the ring

## Context

The countdown ring paints busy calendar time directly on the track: busy arcs are drawn in
`colors.overlay @ .35` (grey) *before* the green remaining sweep
([`DayViewTodayScreen.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt),
the `busyArcs.forEach` pass). Two problems follow:

1. **Upcoming busy is invisible.** The green "remaining" sweep is drawn on top of the busy
   arcs, so any meeting still ahead is repainted over and vanishes. Only past busy — on the
   dark track — reads.
2. **Busy fights the past/future code.** The ring uses colour to mean *time*: dark track =
   past, green = future. Painting busy in grey/green on that same track makes a tint change
   ambiguous ("is this past, or busy?"), and past-busy (grey) has no visual link to
   future-busy. This blurs the past↔future reading the ring is built around.

An earlier iteration (muted-green notches carved into the remaining arc) fixed (1) but made
(2) worse, because it added a *third* busy treatment fighting the same track.

## Goal

Stop painting busy *on* the track. Give calendar blocks their own distinctly-hued layer so
that **hue means "reserved"** and the **surrounding full-width ring keeps meaning
past/future**. Blocks are colored **per calendar**, mirroring the existing detour system,
and rendered as a **hybrid**: a thin colored span-arc plus a small celestial *block* at the
event midpoint.

This reuses the visual vocabulary already in the app: intense-focus is a thin mint arc
(`focusArcs`), detours are warm-colored orbs threaded on the ring (`detourBodies`). Calendar
blocks become the third member of that family.

## Validated decisions

- **Form is hybrid**: a thin colored arc across the event's span (like `focusArcs`, ~0.5×
  main stroke) *plus* a small celestial block at the midpoint (like a detour body, size ∝
  duration). The span-arc shows *where and how long*; the block gives it presence.
- **Colour is per calendar**, assigned from a palette (mirroring `detourSources` /
  `colorIndex`), **not** the calendars' real colors. Real colors are explicitly deferred:
  they would need the macOS EventKit Swift helper to emit a colour field. The assigned
  palette needs zero native change and behaves identically on both platforms.
- **Colour language stays legible**: mint = focus, **cool tones = calendar/reserved** (new
  palette: blues / teal / indigo / violet), warm tones = detour. A block is never confused
  with a detour orb.
- **Block shape** is a rounded square ("celestial block"), distinct from the round detour
  orb, so calendar ≠ detour at a glance.
- **The tag is the calendar name**, shown in the existing hover tooltip (calendar name +
  event title(s) + time range). No permanent on-ring labels — too cluttered.
- **The main ring returns to clean past/future**: the grey past-busy pass and the
  exploratory muted-green notch pass are both removed. Busy no longer touches the track.
- **Net-time math is unchanged in behaviour.** `calculateNetTime` must keep treating busy as
  a *union across calendars* so overlapping events from two calendars are not double-counted.

## Non-goals

- Real per-calendar colors from the OS (deferred; needs native EventKit helper work).
- Any change to the Android home-screen widget ring
  ([`DayViewWidget.kt`](../../../composeApp/src/androidMain/kotlin/fr/dayview/app/DayViewWidget.kt)),
  which does not draw busy at all.
- Persistent on-ring calendar labels; multi-day history; editing calendar events.

## Data model

`BusyInterval` gains a calendar identity — both platforms already read it and currently drop
it:

```kotlin
data class BusyInterval(
    val start: Instant,
    val end: Instant,
    val titles: List<String> = emptyList(),
    val calendarId: String = "",   // new; "" = unknown/unattributed
)
```

- **Desktop** ([`CalendarSource.desktop.kt`](../../../composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt)):
  `calId` is already parsed as `parts[2]`; pass it into `BusyInterval` instead of discarding.
- **Android** ([`CalendarSource.android.kt`](../../../composeApp/src/androidMain/kotlin/fr/dayview/app/CalendarSource.android.kt)):
  `calId` is already read from `CALENDAR_ID`; pass it through.
- Default `""` keeps `NoopCalendarSource`, tests, and any other construction site compiling
  unchanged.

### Merging — two paths

`mergeBusyIntervals` today merges *everything* by time. That is correct for the net-time
math (union) but wrong for rendering (it would fuse two different calendars into one block).

- **Rendering** needs per-calendar grouping: merge only *within the same `calendarId`*, so
  overlapping same-calendar events still coalesce but two calendars stay distinct.
- **Math** (`calculateNetTime`) keeps the union merge, ignoring calendar, so overlaps across
  calendars are counted once.

Approach: keep `mergeBusyIntervals` as the union merge (used by the math, unchanged), and add
a small grouping helper for rendering (group by `calendarId`, merge within each group).

## Colour assignment

New pure projection mirroring `detourSources`:

```kotlin
data class BusyCalendar(val calendarId: String, val colorIndex: Int, val total: Duration)

fun busyCalendars(intervals: List<BusyInterval>): List<BusyCalendar>
```

- `colorIndex` assigned by first-seen order over intervals sorted by start (stable across the
  day, exactly like `detourSources`).
- `total` = summed duration per calendar (available for a future per-calendar tally under the
  dial; not required for v1 rendering).
- The colour is `colors.busy[colorIndex % colors.busy.size]`.

## Rendering

New pure projections in `CalendarNetTime.kt`, both using the `-90° = window start` angle
convention shared with `busyArcs` / `focusArcs` / `detourBodies`:

```kotlin
data class BusyBlockArc(val startAngleDegrees: Float, val sweepDegrees: Float,
                        val colorIndex: Int, val titles: List<String>, val calendarName: String)

data class BusyBlockBody(val angleDegrees: Float, val sizeFraction: Float,
                         val colorIndex: Int, val titles: List<String>, val calendarName: String,
                         val start: Instant, val end: Instant)
```

- `busyBlockArcs(windowStart, windowEnd, intervals, calendarNames)` — one arc per
  within-calendar-merged interval, clipped to the window.
- `busyBlockBodies(windowStart, windowEnd, intervals, calendarNames)` — one body per interval
  at its midpoint, `sizeFraction` from duration clamped to a sensible band (reuse the detour
  `[5 min, 60 min]` band or a busy-specific one), midpoint-outside-window dropped — same rule
  as `detourBodies`.
- `calendarNames: Map<String, String>` (calendarId → display name) comes from
  `availableCalendars()`, already surfaced in state as `availableCalendars`. Missing name
  falls back to a generic "Occupé" label.

In `CountdownCircle`'s `Canvas`:

- **Remove** the grey `busyArcs.forEach` pass and the exploratory muted-green notch pass.
- **Span-arc**: for each `BusyBlockArc`, `drawArc` in `colors.busy[colorIndex]` at ~0.5× main
  stroke (as `focusArcs` do), `StrokeCap.Butt`. Sits as a colored core stripe through the
  ring; the full-width green/dark ring still shows on either side, so past/future survives.
- **Block body**: for each `BusyBlockBody`, draw a rounded-square block at the midpoint in the
  calendar colour, size ∝ `sizeFraction`, with the same soft-halo + specular-highlight
  treatment detour bodies use (`drawRoundRect` instead of `drawCircle`). Straddle the orbit
  like detours, or ride the ring line — chosen during implementation from the rendered proof.
- Draw order: span-arcs and blocks go **after** the green sweep (so they read on top of it in
  the future) and the amber moment marker stays drawn last so it is never occluded.

## Hover / tag

Extend the existing busy hover path (`hitTestBusyArc` + `HoveredBusyArc` tooltip). The
tooltip gains the **calendar name** as its heading, above the event title(s) and the time
range. Block bodies are hit-testable too (reuse the `hitTestDetourBody` radial-band + angular
tolerance approach, adapted to the block orbit). Clicking is informational only (no list to
open, unlike detours).

## Theme

Add a `busy: List<Color>` cool-tone palette to `DayViewColors`, dark and light variants, sized
like `detours` (≈6 entries). Dark: lighter cool tones on the dark ground; Light: deeper cool
tones. Distinct from both `mint` and the warm `detours` list.

## Testing

Pure functions, tested in `commonTest` (`CalendarNetTimeTest`), following the existing
`busyArcs` / `detourSources` / `detourBodies` tests:

- `BusyInterval.calendarId` plumbed: `calculateNetTime` still counts cross-calendar overlaps
  once (add a two-calendar overlapping case).
- Within-calendar merge groups by calendar and merges only same-calendar overlaps.
- `busyCalendars`: stable first-seen `colorIndex`, per-calendar totals.
- `busyBlockArcs`: angle projection, window clipping, one arc per merged interval.
- `busyBlockBodies`: midpoint angle, `sizeFraction` clamp, midpoint-outside-window dropped.
- Calendar-name fallback when the id is unknown / `""`.

UI stays untested for pixels per the repo convention (no `stringResource` assertions in
`runComposeUiTest`); a throwaway screenshot harness is used only for visual proof during
implementation and is not committed.

## Cleanup / migration

The exploratory work on this branch is superseded and is reverted before implementation:

- `upcomingBusyArcs` + its four tests in `CalendarNetTimeTest` (future-notch clipping — not
  needed once busy leaves the track).
- The muted-green notch pass, the `lerp` import, and the temporary preview parameters
  (`busyAheadTintForPreview`, `drawPastBusyForPreview`) in `CountdownCircle`.
- The `RingBusyScreenshotTest` harness (never committed).

## Platform notes

- Desktop and Android both already have `calId` in hand — no native/helper change.
- Real calendar colours (EventKit helper emitting a colour, Android `CALENDAR_COLOR`) are a
  clean follow-up: swap the palette lookup for the real colour where available, keep the
  palette as fallback.
