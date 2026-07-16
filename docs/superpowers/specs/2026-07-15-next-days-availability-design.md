# Next 3 days availability on the day-over screen

## Summary

When today's window is finished, show how much time is available over the next
three days, expressed in net time. The section only appears when net time is
enabled and calendar permission is granted — it earns its screen space by
surfacing per-day variation that a fixed daily window can't show on its own.

## Motivation

Today, when the day's planned window is over, the Today screen rests on a "DAY
OVER" readout with nothing actionable. This feature gives that end-of-day state
a purpose: a glance at what's coming. Because each upcoming day reuses the same
daily window, the useful signal is what the calendar subtracts from it — so the
figure shown is net time (window minus busy events), the same quantity net time
already computes for today.

## Behavior & scope

The "Next 3 days" section renders on the Today screen when **all** of the
following hold:

- today's `dayProgress.isFinished` is true,
- `netTimeSettings.enabled` is true, and
- calendar permission is granted and busy data is present.

When any condition is false, the section is absent and the day-over screen looks
exactly as it does today.

The section shows tomorrow, +2, and +3, each with a single net-available figure:

```
Next 3 days
Tomorrow · 7h 30m
Wed      · 6h 00m
Thu      · 8h 00m
```

Rules:

- **Same window every day.** There is no per-day window configuration in the
  app; each upcoming day reuses the global `startMinutes`/`endMinutes` window.
  Weekends are included (the app has no weekday concept).
- **Available = window duration − busy that day**, where busy uses the same
  included-calendars set and focus-block exclusion as today's net-time
  calculation, with overlapping intervals merged so nothing is double-counted,
  clamped at ≥ 0. A fully-booked day shows `0h 00m`.

## Core & data model (`:core`)

New pure-Kotlin logic, unit-testable, no Compose.

### `dayWindowFor(date, startMinutes, endMinutes, timeZone)`

Generalize the existing `dayWindow` in `CalendarNetTime.kt` — currently pinned to
`localNow` — to take an explicit `LocalDate`. `dayWindow` becomes a thin caller
of `dayWindowFor` for today's date. This is the only change to existing behavior.

### `UpcomingDayAvailability`

```kotlin
data class UpcomingDayAvailability(
    val date: LocalDate,
    val window: Duration,   // gross window (end − start)
    val busy: Duration,     // merged busy within window
    val net: Duration,      // (window − busy).coerceAtLeast(ZERO)
)
```

Carrying `window` and `busy` (not just `net`) keeps a future per-day mini-ring
view a cheap follow-up rather than a data-model change.

### `calculateUpcomingAvailability(fromDate, dayCount, startMinutes, endMinutes, timeZone, busy)`

Builds each day's window via `dayWindowFor`, clips and merges the passed busy
intervals to that window, and returns `List<UpcomingDayAvailability>`. Reuses the
same interval-merging helper that `calculateNetTime` already uses.

### State wiring

- `DayViewUiState.upcomingDays: List<UpcomingDayAvailability>` — non-null but
  **empty** unless `dayProgress.isFinished && netTimeSettings.enabled` and data
  is present.
- `controller.updateUpcomingData(fromDate, days)` — setter mirroring the existing
  `updateNetTimeData` pattern. The busy source excludes focus blocks, same as
  today.

### Calendar fetch (`App.kt`)

A new effect queries `calendarSource.busyIntervals` **once** over the union
window `[tomorrow.start … day+3.end]`, gated on
`isFinished && netTimeSettings.enabled && hasPermission` so nothing runs
otherwise. It reuses the existing `includedCalendarIds` and change-observer
wiring, then calls `updateUpcomingData`.

## UI (`:shared`)

- **`UpcomingDaysSection(days, ...)`** in `DayViewTodayScreen.kt` — a titled
  column of rows, each `day label · formatDurationHm(net)`. Reuses the existing
  `formatDurationHm` so figures render identically to today's net-time value.
- **Day labels:** `Tomorrow` for +1, then localized short weekday names for
  +2/+3. New string resources: `upcoming_title`, `upcoming_tomorrow`. Weekday
  names come from the platform locale, not hardcoded.
- **Placement:**
  - *Compact:* in the main Column directly below `CountdownCircle`, only when
    `progress.isFinished && upcomingDays.isNotEmpty()`.
  - *Wide:* in the side panel below the existing goal/focus panels, same
    condition.
- Renders nothing (not even the title) when `upcomingDays` is empty, so the
  day-over screen with net time off is byte-for-byte unchanged.

## Testing

### Core (`:core:jvmTest`) — primary coverage

`calculateUpcomingAvailability` / `dayWindowFor`:

- No busy → each day's `net == window`.
- Partial busy → `net == window − busy`; `busy`/`window` populated correctly.
- Overlapping busy intervals merged (no double-count).
- Busy outside the window (before start / after end) ignored.
- Fully-booked day → `net == ZERO`, not negative.
- Busy spanning a day boundary clipped to each day's window separately.
- `dayWindowFor` produces correct instants for a given date (including a
  timezone case), and `dayWindow` still matches it for today.

### Controller

- `upcomingDays` empty unless `isFinished && netTimeSettings.enabled`, populated
  once `updateUpcomingData` is fed; focus blocks excluded from busy.

### UI (`:shared` desktopTest)

- Light render test on the pure section composable using seeded data and test
  tags (not `stringResource` assertions, per this repo's Compose-test gotchas):
  section present when finished + data, absent when empty.

### Gate before commit

```bash
./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest
```
