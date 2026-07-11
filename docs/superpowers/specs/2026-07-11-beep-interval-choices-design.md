# Beep Interval Choices — Design

## Goal

Let the user pick the interval between out-of-focus reminder beeps from a fixed set of
values: 5, 10, 15, 20, 25, 30, and 60 minutes. Today the interval is clamped to
30–180 minutes and the Settings stepper moves in ±30 steps, so nothing below
30 minutes is reachable. The default stays 30 minutes.

## Current State

- `SoundSettings.intervalMinutes` (default 30) lives in
  `composeApp/src/commonMain/kotlin/fr/dayview/app/SoundAlerts.kt`.
- `SoundSettings.normalized()` clamps the value with `coerceIn(30, 180)`.
- `SoundAlertScheduler.observe()` independently clamps with `coerceIn(30, 180)`.
- The Settings screen (`DayViewSettingsScreen.kt`) renders −/+ buttons that add or
  subtract 30, enabled while the value stays within 30–180. Accessibility labels
  hardcode the 30-minute step ("Diminuer l’intervalle des rappels de 30 minutes").
- Persistence stores the raw `Int` minutes in the DataStore
  (`DayPreferencesStore.kt`); no normalization happens at read time beyond the
  `?: 30` default.

## Design

### Shared choice ladder

Add to `SoundSettings` a companion object holding the single source of truth:

```kotlin
companion object {
    val INTERVAL_CHOICES: List<Int> = listOf(5, 10, 15, 20, 25, 30, 60)
}
```

Add a small pure helper (companion function) that snaps an arbitrary minute value to
the nearest choice, e.g. `fun snapIntervalMinutes(minutes: Int): Int`. Ties resolve to
the smaller value (only relevant for synthetic inputs; persisted values are either
list members or legacy 60/90/120/150/180).

### Normalization

- `SoundSettings.normalized()` replaces `intervalMinutes.coerceIn(30, 180)` with
  `snapIntervalMinutes(intervalMinutes)`. Legacy persisted values of 90/120/150/180
  become 60; values already in the list are unchanged; the default stays 30.
- `SoundAlertScheduler.observe()` replaces its own `coerceIn(30, 180)` with the same
  helper so scheduler and settings can never disagree. A 5-minute interval over a
  10-hour day yields ~120 loop iterations in the marker scan — negligible.

### Settings UI stepper

In `DayViewSettingsScreen.kt`, the −/+ buttons move one step through
`INTERVAL_CHOICES` instead of adding/subtracting 30:

- Current index = index of the snapped current value in the list.
- "−" selects the previous entry, disabled at 5 (index 0).
- "+" selects the next entry, disabled at 60 (last index).
- Accessibility labels drop the fixed step wording: "Diminuer l’intervalle des
  rappels" / "Augmenter l’intervalle des rappels". The value description keeps
  announcing the current value ("Intervalle des rappels : X minutes").

### Persistence

No format change. The value remains an `Int` of minutes in the DataStore.
Out-of-list legacy values are handled by normalization when the snapshot is used
(same pattern as today's clamping).

## Testing

Update `composeApp/src/commonTest/kotlin/fr/dayview/app/SoundAlertsTest.kt`:

- Normalization: 5 → 5, 7 → 5, 90 → 60, 250 → 60; default remains 30.
- Scheduler: with a 5-minute interval, an `INTERVAL` cue fires at a marker between
  start and end of day.
- Existing tests keep passing (the 30-minute default test is unchanged).

## Out of Scope

- No change to focus-session sounds (`BREAK_REMINDER`) or to volume handling.
- No change to the persistence schema or migration code.
