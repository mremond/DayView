# Deduct Apple Calendar travel time from available time (macOS)

## Goal

When a calendar event has a travel time set in Apple Calendar, that travel time should
count as busy time in DayView, exactly like the event itself: the busy block starts
earlier by the travel duration, and net available time shrinks accordingly. macOS only,
no setting, no UI.

## Motivation

Apple Calendar lets an event carry a "travel time" (5 min to 2 h) that blocks the time
before the event start. That time is not available for anything else, yet DayView
currently ignores it: the busy interval read from EventKit covers only
`[startDate, endDate]`, so net time overstates what is left of the day.

## Platform scope

- **macOS**: the only platform where this applies. Apple stores travel time as a field
  on the event, invisible as a separate calendar entry.
- **Android**: nothing to do. Google Calendar has no travel-time field; when a user adds
  travel time there, Google materialises it as a *separate ordinary event*, which
  `CalendarContract` already returns and DayView already deducts.

## API constraint: private EventKit key

EventKit exposes **no public API** for travel time (verified against the current macOS
SDK headers). The value is reachable via the private KVC key `travelTime` (seconds, as a
number). DayView is distributed directly (not App Store), so using it is acceptable, but
the read must be defensive:

- Guard with `event.responds(to: NSSelectorFromString("travelTime"))` before
  `value(forKey:)`, so an OS release that removes the accessor degrades silently to
  "no travel time" instead of raising an Objective-C exception.
- Clamp the value to `[0, 3 h]` (`MAX_TRAVEL`). Apple's UI caps at 2 h; the clamp
  protects against a corrupt or absurd value swallowing the day, and matches the widened
  fetch window below.

## Behavior

All changes live in `dv_calendar_busy` in `scripts/MacEventKitBridge.swift`. No Kotlin
changes, no encoding-format changes.

1. **Extend the emitted interval.** For each event that passes the existing filters
   (not all-day, availability busy, not declined/tentative), read the clamped travel
   time and emit `[startDate − travel, endDate]` instead of `[startDate, endDate]`.
   Everything downstream (net time, ring arcs, hover overlay, history, sync) consumes
   `BusyInterval` and inherits the deduction automatically. Visually the block lengthens
   upstream — the same rendering Apple Calendar itself uses.

2. **Widen the fetch predicate.** Query EventKit with `end + MAX_TRAVEL` (+3 h) instead
   of `end`, so an event that starts *after* the day window but whose travel overlaps it
   is still seen (dinner at 22:30 with 45 min travel, day ending at 22:00 → 15 min of
   travel inside the day). Then keep the bridge's contract intact by emitting only
   extended intervals that overlap the *requested* `[start, end]`: skip an event when
   `startDate − travel ≥ end` (the requested end). Downstream clipping
   (`busyIntervalsWithinWindow`, `busyBlockArcs`) already trims any overhang, including
   in history.

## Rejected alternatives

- **Separate "travel" interval with its own title.** `mergeBusyIntervalsByCalendar`
  merges the two contiguous intervals into one arc anyway; the only gain would be a
  "travel" line in the hover title list. Cosmetic, not worth the extra emission logic.
- **`travel` field on `BusyInterval`.** Unit-testable in common Kotlin and would allow a
  dedicated rendering, but requires a codec/format migration (history + sync) for a
  speculative visual gain. YAGNI.

## Testing

The computation lives in the Swift bridge, which has no test harness; the Kotlin
downstream is unchanged and already covered (`CalendarNetTimeTest`). Verification:

- Manual end-to-end on macOS: create an event with a travel time in Calendar.app, run
  the desktop app, confirm the busy block starts earlier by the travel duration and net
  time shrinks by the same amount. Confirm an event just after the day window with
  travel overlapping the window contributes the overlapping part.
- Run the standard gate to confirm no regression:
  `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`.

## Out of scope

- Any user-facing toggle or setting.
- Android changes of any kind.
- Changes to the `BusyInterval` model, persistence, encoding, or sync.
- Distinct visual treatment of the travel portion (hatched arc, hover label).
