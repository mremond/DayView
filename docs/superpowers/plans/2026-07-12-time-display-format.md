# OS-Driven 12h/24h Time Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render every user-facing wall-clock time in 12-hour or 24-hour form according to the operating system's clock preference.

**Architecture:** A single pure formatter (`formatWallClock`) owns the 12h/24h logic. An `expect`/`actual` provider detects the OS preference and publishes it through a `CompositionLocal` (`LocalUses24HourClock`, default `true`). Display call sites and both input pickers read that flag. The goal deadline's canonical `dd/MM/yyyy HH:mm` data string is untouched — only its picker widget follows the OS.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Material3, kotlin.test.

## Global Constraints

- ktlint is enforced. Run `./gradlew ktlintCheck` (or `ktlintFormat`) before each commit.
- Full gate before finishing: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- No reference to Claude/Anthropic/AI in commit messages.
- Avoid duplicated code; keep functions small and focused.
- The goal canonical string (`formatGoalDeadline`, `formatGoalPickerInput`) and its parser `parseGoalDeadline` stay strict 24h — do not change them.
- 12h hour is NOT zero-padded (`7:05 AM`); minutes always padded; 24h keeps both padded (`07:05`).

---

### Task 1: Pure `formatWallClock` formatter

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Produces: `fun formatWallClock(hour: Int, minute: Int, use24Hour: Boolean): String`

- [ ] **Step 1: Write the failing tests**

Add to `CalendarNetTimeTest.kt` (inside the existing test class):

```kotlin
@Test
fun formatWallClock_24h_pads_both_fields() {
    assertEquals("00:00", formatWallClock(0, 0, use24Hour = true))
    assertEquals("07:05", formatWallClock(7, 5, use24Hour = true))
    assertEquals("13:30", formatWallClock(13, 30, use24Hour = true))
}

@Test
fun formatWallClock_12h_uses_am_pm_without_hour_padding() {
    assertEquals("12:00 AM", formatWallClock(0, 0, use24Hour = false))
    assertEquals("7:05 AM", formatWallClock(7, 5, use24Hour = false))
    assertEquals("12:00 PM", formatWallClock(12, 0, use24Hour = false))
    assertEquals("1:05 PM", formatWallClock(13, 5, use24Hour = false))
    assertEquals("11:59 PM", formatWallClock(23, 59, use24Hour = false))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — `formatWallClock` unresolved reference.

- [ ] **Step 3: Implement `formatWallClock`**

Add to `CalendarNetTime.kt`, immediately above `formatClockHm` (around line 100):

```kotlin
/**
 * Wall-clock label. 24h → "07:05"; 12h → "7:05 AM", noon "12:00 PM",
 * midnight "12:00 AM". No zero-padding on the 12h hour; minute always padded.
 */
fun formatWallClock(hour: Int, minute: Int, use24Hour: Boolean): String {
    val mm = minute.toString().padStart(2, '0')
    if (use24Hour) return "${hour.toString().padStart(2, '0')}:$mm"
    val period = if (hour < 12) "AM" else "PM"
    val h12 = (hour % 12).let { if (it == 0) 12 else it }
    return "$h12:$mm $period"
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "Add formatWallClock for 12h/24h wall-clock labels"
```

---

### Task 2: OS preference detection + CompositionLocal

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/ClockPreference.kt`
- Create: `composeApp/src/androidMain/kotlin/fr/dayview/app/ClockPreference.android.kt`
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/ClockPreference.desktop.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTheme.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/ClockPreferenceTest.kt`

**Interfaces:**
- Produces: `val LocalUses24HourClock: ProvidableCompositionLocal<Boolean>` (default `true`)
- Produces: `@Composable expect fun rememberUses24HourClock(): Boolean`
- Produces (desktop, internal): `fun jvmUses24HourClock(locale: Locale = Locale.getDefault()): Boolean`

- [ ] **Step 1: Write the failing desktop detection test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/ClockPreferenceTest.kt`:

```kotlin
package fr.dayview.app

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockPreferenceTest {
    @Test
    fun us_locale_is_12_hour() {
        assertEquals(false, jvmUses24HourClock(Locale.US))
    }

    @Test
    fun france_locale_is_24_hour() {
        assertEquals(true, jvmUses24HourClock(Locale.FRANCE))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.ClockPreferenceTest"`
Expected: FAIL — `jvmUses24HourClock` unresolved reference.

- [ ] **Step 3: Create the common declarations**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/ClockPreference.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether time labels should render in 24-hour form. Defaults to `true` so
 * existing tests and previews keep their 24h rendering unless a provider
 * overrides it near the app root.
 */
val LocalUses24HourClock = staticCompositionLocalOf { true }

/** Reads the operating system's 12h/24h clock preference. */
@Composable
expect fun rememberUses24HourClock(): Boolean
```

- [ ] **Step 4: Create the Android actual**

Create `composeApp/src/androidMain/kotlin/fr/dayview/app/ClockPreference.android.kt`:

```kotlin
package fr.dayview.app

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberUses24HourClock(): Boolean {
    val context = LocalContext.current
    return remember(context) { DateFormat.is24HourFormat(context) }
}
```

- [ ] **Step 5: Create the desktop actual + detection helper**

Create `composeApp/src/desktopMain/kotlin/fr/dayview/app/ClockPreference.desktop.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads the JVM locale's short time pattern. A pattern containing 'h'/'K'
 * (12-hour cycles) or 'a' (am/pm marker) means the locale uses a 12-hour clock.
 */
internal fun jvmUses24HourClock(locale: Locale = Locale.getDefault()): Boolean {
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)
        ?.toPattern()
        .orEmpty()
    return !(pattern.contains('h') || pattern.contains('K') || pattern.contains('a'))
}

@Composable
actual fun rememberUses24HourClock(): Boolean = remember { jvmUses24HourClock() }
```

- [ ] **Step 6: Run the detection test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.ClockPreferenceTest"`
Expected: PASS.

- [ ] **Step 7: Provide the local at the app root**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTheme.kt`, replace the provider block (around line 97):

```kotlin
    CompositionLocalProvider(LocalDayViewColors provides colors) {
```

with:

```kotlin
    CompositionLocalProvider(
        LocalDayViewColors provides colors,
        LocalUses24HourClock provides rememberUses24HourClock(),
    ) {
```

(No new import needed: `CompositionLocalProvider` is already imported; `LocalUses24HourClock` and `rememberUses24HourClock` are in the same package.)

- [ ] **Step 8: Run ktlint and the desktop test suite**

Run: `./gradlew ktlintCheck :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/ClockPreference.kt \
        composeApp/src/androidMain/kotlin/fr/dayview/app/ClockPreference.android.kt \
        composeApp/src/desktopMain/kotlin/fr/dayview/app/ClockPreference.desktop.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTheme.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/ClockPreferenceTest.kt
git commit -m "Detect OS 12h/24h clock preference and expose it via CompositionLocal"
```

---

### Task 3: Route display labels through the OS flag

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt` (`formatClockHm`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsComponents.kt` (`TimePreferenceRow`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt` (day summary)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt` (`formatMinutesOfDay` + call sites)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (hover clock labels)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Consumes: `formatWallClock(hour, minute, use24Hour)` (Task 1); `LocalUses24HourClock` (Task 2)
- Produces: `fun formatClockHm(instant: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault(), use24Hour: Boolean = true): String`

- [ ] **Step 1: Write the failing 12h test for `formatClockHm`**

Add to `CalendarNetTimeTest.kt`:

```kotlin
@Test
fun formatClockHm_respects_12h_flag() {
    val zone = TimeZone.UTC
    val instant = LocalDateTime(2026, 1, 1, 13, 0).toInstant(zone)
    assertEquals("13:00", formatClockHm(instant, zone))
    assertEquals("1:00 PM", formatClockHm(instant, zone, use24Hour = false))
}
```

Ensure these imports exist in the test file (add any that are missing):
`import kotlinx.datetime.LocalDateTime`, `import kotlinx.datetime.TimeZone`, `import kotlinx.datetime.toInstant`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — `formatClockHm` has no `use24Hour` parameter.

- [ ] **Step 3: Update `formatClockHm` to delegate to `formatWallClock`**

In `CalendarNetTime.kt`, replace:

```kotlin
fun formatClockHm(instant: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val value = instant.toLocalDateTime(timeZone)
    return "${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"
}
```

with:

```kotlin
fun formatClockHm(
    instant: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    use24Hour: Boolean = true,
): String {
    val value = instant.toLocalDateTime(timeZone)
    return formatWallClock(value.hour, value.minute, use24Hour)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS (existing `formatClockHm(x, zone)` calls keep 24h via the default).

- [ ] **Step 5: Update `TimePreferenceRow` (day start/end big label)**

In `SettingsComponents.kt`, in `TimePreferenceRow`, add the flag next to the existing colors line:

```kotlin
    val colors = LocalDayViewColors.current
    val uses24Hour = LocalUses24HourClock.current
```

Then replace the label Text value:

```kotlin
                "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}",
```

with:

```kotlin
                formatWallClock(hour, minute, uses24Hour),
```

- [ ] **Step 6: Update the day settings summary**

In `DayViewSettingsScreen.kt`, in `categorySummary`, replace the `SettingsCategory.DAY` branch:

```kotlin
    SettingsCategory.DAY -> {
        val p = state.dayProgress
        val start = "${p.startHour.toString().padStart(2, '0')}:${p.startMinute.toString().padStart(2, '0')}"
        val end = "${p.endHour.toString().padStart(2, '0')}:${p.endMinute.toString().padStart(2, '0')}"
        stringResource(Res.string.settings_summary_day, start, end)
    }
```

with:

```kotlin
    SettingsCategory.DAY -> {
        val p = state.dayProgress
        val uses24Hour = LocalUses24HourClock.current
        val start = formatWallClock(p.startHour, p.startMinute, uses24Hour)
        val end = formatWallClock(p.endHour, p.endMinute, uses24Hour)
        stringResource(Res.string.settings_summary_day, start, end)
    }
```

- [ ] **Step 7: Update `formatMinutesOfDay` and its detour call sites**

In `DetoursUi.kt`, change the helper (around line 503):

```kotlin
private fun formatMinutesOfDay(minutes: Int): String {
    val safe = minutes.coerceIn(0, 23 * 60 + 59)
    return "${(safe / 60).toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
}
```

to:

```kotlin
private fun formatMinutesOfDay(minutes: Int, use24Hour: Boolean): String {
    val safe = minutes.coerceIn(0, 23 * 60 + 59)
    return formatWallClock(safe / 60, safe % 60, use24Hour)
}
```

Then, in each of the three composables that call it or `formatClockHm` — `DetourCaptureContent`, `DetourListDialog`, and `DetourEditForm` — add below their existing `val colors = LocalDayViewColors.current` line:

```kotlin
    val uses24Hour = LocalUses24HourClock.current
```

Update every `formatMinutesOfDay(startMinutes)` call in those composables to `formatMinutesOfDay(startMinutes, uses24Hour)`, and every `formatClockHm(episode.start)` / `formatClockHm(episode.end)` call to pass `use24Hour = uses24Hour` (e.g. `formatClockHm(episode.start, use24Hour = uses24Hour)`).

- [ ] **Step 8: Update the today-screen hover clock labels**

In `DayViewTodayScreen.kt`, in `CountdownCircle`, add below its `val colors = LocalDayViewColors.current` line:

```kotlin
    val uses24Hour = LocalUses24HourClock.current
```

Then update the four hover-overlay `formatClockHm(...)` calls (the busy-arc `startLabel`/`endLabel` around lines 958–967 and the detour body labels around lines 1018–1019) to pass `use24Hour = uses24Hour`. For the busy-arc calls that already pass an instant argument, add the named flag, e.g.:

```kotlin
                    val startLabel = formatClockHm(
                        angleToInstant(arc.startAngleDegrees, windowStart, windowEnd),
                        use24Hour = uses24Hour,
                    )
```

and for the detour body:

```kotlin
                                    formatClockHm(body.start, use24Hour = uses24Hour),
                                    formatClockHm(body.end, use24Hour = uses24Hour),
```

- [ ] **Step 9: Run ktlint and the full test gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL (existing UI tests still render 24h via the `true` default local).

- [ ] **Step 10: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsComponents.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "Render time labels in 12h/24h per OS preference"
```

---

### Task 4: Pickers follow the OS (goal TimeInput + desktop Compose picker)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (goal `TimeInput`)
- Rewrite: `composeApp/src/desktopMain/kotlin/fr/dayview/app/TimePickerLauncher.desktop.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`

**Interfaces:**
- Consumes: `LocalUses24HourClock` (Task 2); the unchanged `TimePickerLauncher` interface and `PendingTimeRequest`-style state.

- [ ] **Step 1: Make the goal time picker follow the OS**

In `DayViewTodayScreen.kt`, in `GoalDateTimeDialog`, replace:

```kotlin
    val timeState = rememberTimePickerState(
        initialHour = goalPickerHour(initial),
        initialMinute = goalPickerMinute(initial),
        is24Hour = true,
    )
```

with:

```kotlin
    val timeState = rememberTimePickerState(
        initialHour = goalPickerHour(initial),
        initialMinute = goalPickerMinute(initial),
        is24Hour = LocalUses24HourClock.current,
    )
```

(The confirm path still calls `formatGoalPickerInput(day, timeState.hour, timeState.minute)`, which emits the canonical 24h string regardless of display mode — nothing else changes.)

- [ ] **Step 2: Rewrite the desktop picker as a Compose Material dialog**

Replace the entire contents of `composeApp/src/desktopMain/kotlin/fr/dayview/app/TimePickerLauncher.desktop.kt` with:

```kotlin
package fr.dayview.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.desktop_choose_time
import fr.dayview.app.generated.resources.dialog_cancel
import fr.dayview.app.generated.resources.dialog_ok
import org.jetbrains.compose.resources.stringResource

private data class PendingTimeRequest(
    val initialMinutes: Int,
    val allowedMinutes: IntRange,
    val onTimeSelected: (Int) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun rememberTimePickerLauncher(): TimePickerLauncher {
    var request by remember { mutableStateOf<PendingTimeRequest?>(null) }
    val use24Hour = LocalUses24HourClock.current
    request?.let { req ->
        val safeInitial = req.initialMinutes.coerceIn(req.allowedMinutes)
        val state = rememberTimePickerState(
            initialHour = safeInitial / 60,
            initialMinute = safeInitial % 60,
            is24Hour = use24Hour,
        )
        AlertDialog(
            onDismissRequest = { request = null },
            title = { Text(stringResource(Res.string.desktop_choose_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val selected = (state.hour * 60 + state.minute).coerceIn(req.allowedMinutes)
                    req.onTimeSelected(selected)
                    request = null
                }) { Text(stringResource(Res.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { request = null }) { Text(stringResource(Res.string.dialog_cancel)) }
            },
        )
    }
    return remember {
        TimePickerLauncher { initialMinutes, allowedMinutes, onTimeSelected ->
            request = PendingTimeRequest(initialMinutes, allowedMinutes, onTimeSelected)
        }
    }
}
```

- [ ] **Step 3: Remove the now-unused desktop picker strings**

The Swing-only `desktop_hour` / `desktop_minute` strings are no longer referenced. Delete these two lines from `composeApp/src/commonMain/composeResources/values/strings.xml`:

```xml
    <string name="desktop_hour">Hour</string>
    <string name="desktop_minute">Minute</string>
```

Delete the matching French lines from `composeApp/src/commonMain/composeResources/values-fr/strings.xml` (line 193, `desktop_hour`, and its `desktop_minute` neighbour). Keep `desktop_choose_time` — the new dialog still uses it.

- [ ] **Step 4: Verify no dangling references to the deleted strings**

Run: `grep -rn "desktop_hour\|desktop_minute" composeApp/src`
Expected: no matches.

- [ ] **Step 5: Run ktlint and the full test gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manually verify the desktop picker**

Run: `./gradlew :composeApp:run`
Open Settings → Day, tap a time row: the Compose time picker appears in 24h or AM/PM form matching the OS. Confirm the selection updates the row label. Close the app.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/desktopMain/kotlin/fr/dayview/app/TimePickerLauncher.desktop.kt \
        composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml
git commit -m "Make time pickers follow the OS 12h/24h preference"
```

---

## Notes for the implementer

- **Why the default `true`:** UI tests and `@Preview`s that never install a provider keep rendering 24h, so no existing snapshot/assertion changes. Only the real app root (Task 2, Step 7) installs the OS-driven value.
- **Do not touch** `formatGoalDeadline`, `formatGoalPickerInput`, or `parseGoalDeadline` — they are a 24h data format, not a display string.
- **Android picker** already honours the OS (`DateFormat.is24HourFormat`); it is intentionally left unchanged.
- If ktlint complains about import ordering in the rewritten desktop file, run `./gradlew ktlintFormat` and re-stage.
