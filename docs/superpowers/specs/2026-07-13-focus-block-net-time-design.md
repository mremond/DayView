# Focus-block events excluded from busy time

## Problem

A busy calendar event is currently subtracted from the day's *net available time*: it
counts as an obligation that eats into the productive part of the day. But some busy
events are the opposite — time the user deliberately reserves on their calendar to work
on their main goal ("focus" blocks). Those should not shrink the net day, yet they must
stay visible on the ring so the reserved time is legible.

## Behavior

A calendar event that is marked BUSY **and** whose title contains `focus`
(case-insensitive substring — "Focus", "FOCUS", "deep focus block" all match):

- **Still draws on the ring** as a normal busy arc. No visual distinction from other
  busy calendars; same per-calendar color.
- **Does not subtract** from net available time (`netDay`, `netRemaining`,
  `busyRemaining` are all computed as if the event were not busy).

Non-focus busy events are unaffected.

Overlapping busy events are counted once, for the union of their spans — this is
existing behavior via `mergeBusyIntervals` and must be preserved.

## Implementation

Two small, isolated changes in the shared module. No new settings, no storage / sync /
history-format changes, no platform-specific code. Event titles already flow through
`BusyInterval.titles` on both Android and macOS.

### 1. `CalendarNetTime.kt` — classification predicate

Add next to `BusyInterval`:

```kotlin
/** A busy event reserved for goal work: any title contains "focus" (case-insensitive). */
fun BusyInterval.isFocusBlock(): Boolean =
    titles.any { it.contains("focus", ignoreCase = true) }
```

`titles.any` operates on the raw per-event intervals (one title each) as produced by the
calendar sources, before any merge collapses them.

### 2. `DayViewController.kt` — diverge the two consumers at the call site

`busyIntervalsToday` currently feeds both `busyBlockArcsState` (ring) and `netTime`
(subtraction). Keep the ring input unfiltered; filter focus blocks out of the net-time
input only:

```kotlin
val netTime: NetTime?
    get() = if (netTimeSettings.enabled) {
        val (start, end) = dayWindow
        calculateNetTime(
            dayProgress, dayNow, start, end,
            busyIntervalsToday.filterNot { it.isFocusBlock() },
        )
    } else {
        null
    }
```

`busyBlockArcsState` is left untouched, so focus blocks keep rendering.

Because `state.netTime` is a single property consumed by both the today screen and the
history day screen, this one change covers every net-time display.

### Why this shape

`calculateNetTime` and `busyBlockArcs` already accept the busy list as a parameter, so
diverging their inputs at the call site is the minimal, non-invasive change. Filtering
before `calculateNetTime`'s internal `mergeBusyIntervals` means the remaining meetings
are still unioned — overlaps are not double-counted.

## Testing

Unit tests in `CalendarNetTimeTest.kt`:

1. A `focus`-titled busy interval does not reduce `netDay` / `netRemaining` /
   `busyRemaining`.
2. A normal busy interval still reduces net time (regression guard).
3. Case-insensitivity: "Focus", "FOCUS", "deep focus block" each match; a title with no
   "focus" substring does not.
4. A focus block overlapping a real meeting: only the meeting's span is subtracted.
5. Two overlapping real meetings are counted once (union), independent of the focus
   filter (regression guard for "not counted twice").
