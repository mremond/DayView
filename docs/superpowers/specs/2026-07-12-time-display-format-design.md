# Time display format (12h / 24h from the OS) — Design

## Goal

Render wall-clock times in 12-hour or 24-hour form according to the operating
system's clock preference, across every user-facing time in the app. When the OS
is in 12-hour mode, show `7:05 AM`; otherwise show `07:05`.

## Current state

- **Android time picker** already honours the OS via
  `DateFormat.is24HourFormat(context)` in
  `TimePickerLauncher.android.kt`.
- **Desktop time picker** is a Swing `JSpinner` fixed to `0..23` — always 24h
  (`TimePickerLauncher.desktop.kt`).
- **Every rendered time label is hard-coded 24h** through scattered
  `padStart(2, '0')` expressions:
  - day start/end big label — `SettingsComponents.kt` (`TimePreferenceRow`)
  - settings summary `07:00 – 19:00` — `DayViewSettingsScreen.kt`
  - detour start time — `formatMinutesOfDay` in `DetoursUi.kt`
  - detour episode / today-screen body clock — `formatClockHm` in `CalendarNetTime.kt`
- **Goal deadline** uses a canonical `dd/MM/yyyy HH:mm` string
  (`formatGoalDeadline` / `formatGoalPickerInput`) that is **parsed back** by
  `parseGoalDeadline` (strict 24h regex). This is a data carrier, not a display
  format, and must stay 24h. The goal's `TimeInput` widget hard-codes
  `is24Hour = true` (`DayViewTodayScreen.kt`).

## Scope

All user-facing time **displays**: day start/end, detour start, detour/today
clock labels, and both input pickers (desktop + goal). The goal deadline's
canonical parse/round-trip string is explicitly **out of scope** and stays 24h.

## Design

### 1. OS preference detection (`expect` / `actual`)

A Composable provider in `commonMain`:

```kotlin
@Composable expect fun rememberUses24HourClock(): Boolean
```

- **Android** (`androidMain`): `DateFormat.is24HourFormat(LocalContext.current)`,
  wrapped in `remember(context)`.
- **Desktop** (`desktopMain`): `remember { jvmUses24HourClock() }`.

```kotlin
// desktopMain — testable, locale injectable
internal fun jvmUses24HourClock(locale: Locale = Locale.getDefault()): Boolean {
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)
        ?.toPattern().orEmpty()
    // 'h'/'K' (12h cycles) or 'a' (am/pm marker) ⇒ 12-hour clock.
    return !(pattern.contains('h') || pattern.contains('K') || pattern.contains('a'))
}
```

The value is published once, near the app root where the theme/colors are
provided, through a `CompositionLocal` so no prop-drilling is needed:

```kotlin
val LocalUses24HourClock = staticCompositionLocalOf { true }
// at root: CompositionLocalProvider(LocalUses24HourClock provides rememberUses24HourClock()) { ... }
```

Any composable reads `LocalUses24HourClock.current`. Default `true` keeps every
existing test and preview rendering 24h unless a provider overrides it.

### 2. Single formatter (pure, unit-tested)

```kotlin
/**
 * Wall-clock label. 24h → "07:05". 12h → "7:05 AM", noon "12:00 PM",
 * midnight "12:00 AM". No zero-padding on the 12h hour; minute always padded.
 */
fun formatWallClock(hour: Int, minute: Int, use24Hour: Boolean): String
```

This is the one place that knows AM/PM and the `0→12` / `13→1` mapping. The
existing duplicated `padStart` call sites converge onto it:

- `formatMinutesOfDay(minutes)` → `formatWallClock(minutes / 60, minutes % 60, use24)`
- `formatClockHm(instant, tz, use24)` → delegates to `formatWallClock`
- `TimePreferenceRow` big label → `formatWallClock`
- `DayViewSettingsScreen` summary → `formatWallClock`

`formatClockHm` and `formatMinutesOfDay` gain a `use24Hour` parameter; their
composable callers pass `LocalUses24HourClock.current`.

### 3. Display wiring

- **Day start/end**: big label (`TimePreferenceRow`) + settings summary.
- **Detour**: start time (`formatMinutesOfDay`) + episode clock (`formatClockHm`)
  in `DetoursUi.kt` and the today-screen body labels in `DayViewTodayScreen.kt`.
- **Goal**: only `TimeInput(is24Hour = LocalUses24HourClock.current)` — canonical
  string and `parseGoalDeadline` untouched.

### 4. Input pickers

- **Android**: already correct — no change.
- **Desktop**: replace the Swing `JSpinner` dialog with a Compose Material
  time-picker dialog driven by composition state, unifying with the goal picker.

  `rememberTimePickerLauncher()` (already `@Composable`) holds a pending-request
  state and renders an `AlertDialog` hosting Material3 `TimePicker` when a request
  is active:

  ```kotlin
  @Composable actual fun rememberTimePickerLauncher(): TimePickerLauncher {
      var request by remember { mutableStateOf<PendingTimeRequest?>(null) }
      val use24 = LocalUses24HourClock.current
      request?.let { req ->
          val state = rememberTimePickerState(
              initialHour = req.initialMinutes / 60,
              initialMinute = req.initialMinutes % 60,
              is24Hour = use24,
          )
          // AlertDialog { TimePicker(state) } — confirm:
          //   req.onTimeSelected((state.hour*60 + state.minute).coerceIn(req.allowedMinutes))
          //   request = null   (also cleared on dismiss)
      }
      return remember { TimePickerLauncher { initial, allowed, cb -> request = PendingTimeRequest(initial, allowed, cb) } }
  }
  ```

  The `allowedMinutes` range keeps its post-confirm `coerceIn` (Material's picker
  has no native min/max), matching today's behaviour. The `TimePickerLauncher`
  common interface is unchanged. Obsolete desktop-only Swing strings
  (`desktop_hour`, `desktop_minute`) are removed if no longer referenced.

### 5. Tests

- `commonTest`: `formatWallClock` — midnight (`12:00 AM` / `00:00`), noon
  (`12:00 PM` / `12:00`), a morning and an afternoon time, minute padding, and
  the no-zero-pad 12h hour.
- `desktopTest`: `jvmUses24HourClock(Locale.US)` → false, `jvmUses24HourClock(Locale.FRANCE)` → true.
- Existing UI tests keep rendering 24h via the `staticCompositionLocalOf { true }`
  default (no reliance on `stringResource` text, per project test conventions).

## Decisions

- Desktop picker moves from Swing to Compose Material `TimePicker`.
- 12h hour is **not** zero-padded (`7:05 AM`), matching macOS/Android convention.
- Goal canonical `dd/MM/yyyy HH:mm` string stays 24h; only its picker widget
  follows the OS.

## Out of scope

- Localised AM/PM wording beyond `AM`/`PM`.
- Changing stored/serialised time formats or the goal deadline parser.
