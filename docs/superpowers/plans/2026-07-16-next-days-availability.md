# Next 3 days availability — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When today's window is finished and net time is enabled, show the net-available hours for each of the next three days below the resting ring on the Today screen.

**Architecture:** Pure per-day availability logic lives in `:core` (new `dayWindowFor`, `UpcomingDayAvailability`, `calculateUpcomingAvailability`, `upcomingUnionWindow`). The controller stores a transient future-busy layer and derives the day list in an accessor gated on `isFinished && netTimeSettings.enabled`. `App.kt` fetches the future busy layer once via the existing `CalendarSource`. A new `UpcomingDaysSection` composable renders the rows on the Today screen.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime 0.8.0, kotlin.test.

## Global Constraints

- JDK 21 toolchain; ktlint enforced — run `./gradlew ktlintFormat` before committing.
- No reference to Claude/Anthropic/AI in commit messages or PRs; no "test plan" / "verification" sections in commit messages.
- Do not reference this plan or any `docs/superpowers/` document in commit messages.
- Follow existing package `fr.dayview.app`; core code stays Compose-free.
- Compose resources: single `%` in format strings; new strings go in `shared/src/commonMain/composeResources/values/strings.xml` and are imported individually (`import fr.dayview.app.generated.resources.<name>`).
- Compose UI tests: never assert `stringResource` text — assert on `testTag` nodes only.
- Full gate before every commit:
  `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

---

### Task 1: Generalize the day window to any date (`:core`)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt` (add `dayWindowFor`, make `dayWindow` delegate; lines 187-204)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayWindowForTest.kt` (create)

**Interfaces:**
- Produces:
  - `fun dayWindowFor(date: LocalDate, startMinutesOfDay: Int, endMinutesOfDay: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()): Pair<Instant, Instant>`
  - `fun dayWindow(now: Instant, startMinutesOfDay: Int, endMinutesOfDay: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()): Pair<Instant, Instant>` (unchanged signature, now delegates)

- [ ] **Step 1: Write the failing test**

Create `core/src/commonTest/kotlin/fr/dayview/app/DayWindowForTest.kt`:

```kotlin
package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class DayWindowForTest {
    @Test
    fun buildsWindowForGivenDateInUtc() {
        val date = LocalDate(2026, 1, 15)
        val (start, end) = dayWindowFor(date, 8 * 60, 18 * 60, TimeZone.UTC)
        assertEquals(LocalDateTime(2026, 1, 15, 8, 0).toInstant(TimeZone.UTC), start)
        assertEquals(LocalDateTime(2026, 1, 15, 18, 0).toInstant(TimeZone.UTC), end)
    }

    @Test
    fun dayWindowDelegatesToTodaysLocalDate() {
        val now = LocalDateTime(2026, 1, 15, 12, 30).toInstant(TimeZone.UTC)
        assertEquals(
            dayWindowFor(LocalDate(2026, 1, 15), 8 * 60, 18 * 60, TimeZone.UTC),
            dayWindow(now, 8 * 60, 18 * 60, TimeZone.UTC),
        )
    }

    @Test
    fun windowMinutesAreClampedToLegalRange() {
        // start below 0 clamps to 0; end below start+30 clamps up to start+30.
        val (start, end) = dayWindowFor(LocalDate(2026, 1, 15), -100, 10, TimeZone.UTC)
        assertEquals(LocalDateTime(2026, 1, 15, 0, 0).toInstant(TimeZone.UTC), start)
        assertEquals(LocalDateTime(2026, 1, 15, 0, 30).toInstant(TimeZone.UTC), end)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayWindowForTest"`
Expected: FAIL — `dayWindowFor` unresolved reference.

- [ ] **Step 3: Add `dayWindowFor` and delegate `dayWindow`**

In `CalendarNetTime.kt`, add the import near the other kotlinx.datetime imports (top of file):

```kotlin
import kotlinx.datetime.LocalDate
```

Replace the existing `dayWindow` function (lines 187-204) with:

```kotlin
fun dayWindowFor(
    date: LocalDate,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Instant, Instant> {
    val safeStart = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutesOfDay.coerceIn(safeStart + 30, 23 * 60 + 59)
    fun at(minutes: Int) = LocalDateTime(
        year = date.year,
        month = date.month,
        day = date.day,
        hour = minutes / 60,
        minute = minutes % 60,
    ).toInstant(timeZone)
    return at(safeStart) to at(safeEnd)
}

fun dayWindow(
    now: Instant,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Instant, Instant> = dayWindowFor(
    now.toLocalDateTime(timeZone).date,
    startMinutesOfDay,
    endMinutesOfDay,
    timeZone,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayWindowForTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
        core/src/commonTest/kotlin/fr/dayview/app/DayWindowForTest.kt
git commit -m "Generalize day window construction to an arbitrary date"
```

---

### Task 2: Per-day upcoming availability logic (`:core`)

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/UpcomingAvailability.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/UpcomingAvailabilityTest.kt`

**Interfaces:**
- Consumes: `dayWindowFor` (Task 1), `mergeBusyIntervals`, `BusyInterval` (existing).
- Produces:
  - `const val UPCOMING_DAY_COUNT = 3`
  - `data class UpcomingDayAvailability(val date: LocalDate, val window: Duration, val busy: Duration, val net: Duration)`
  - `fun calculateUpcomingAvailability(fromDate: LocalDate, dayCount: Int, startMinutes: Int, endMinutes: Int, busy: List<BusyInterval>, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<UpcomingDayAvailability>`
  - `fun upcomingUnionWindow(fromDate: LocalDate, dayCount: Int, startMinutes: Int, endMinutes: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()): Pair<Instant, Instant>`

- [ ] **Step 1: Write the failing test**

Create `core/src/commonTest/kotlin/fr/dayview/app/UpcomingAvailabilityTest.kt`:

```kotlin
package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class UpcomingAvailabilityTest {
    private val tz = TimeZone.UTC
    private val from = LocalDate(2026, 1, 15)

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime(2026, 1, day, hour, minute).toInstant(tz)

    private fun busy(startDay: Int, startHour: Int, endDay: Int, endHour: Int, vararg titles: String) =
        BusyInterval(at(startDay, startHour), at(endDay, endHour), titles.toList())

    private fun compute(busy: List<BusyInterval>) =
        calculateUpcomingAvailability(from, 3, 8 * 60, 18 * 60, busy, tz)

    @Test
    fun noBusyMeansNetEqualsFullWindow() {
        val days = compute(emptyList())
        assertEquals(3, days.size)
        days.forEach {
            assertEquals(10.hours, it.window)
            assertEquals(Duration.ZERO, it.busy)
            assertEquals(10.hours, it.net)
        }
        assertEquals(LocalDate(2026, 1, 15), days[0].date)
        assertEquals(LocalDate(2026, 1, 17), days[2].date)
    }

    @Test
    fun partialBusySubtractsFromNet() {
        val days = compute(listOf(busy(15, 9, 15, 11)))
        assertEquals(2.hours, days[0].busy)
        assertEquals(8.hours, days[0].net)
        assertEquals(10.hours, days[1].net)
    }

    @Test
    fun overlappingBusyIsMergedNotDoubleCounted() {
        val days = compute(listOf(busy(15, 9, 15, 11), busy(15, 10, 15, 12)))
        assertEquals(3.hours, days[0].busy)
        assertEquals(7.hours, days[0].net)
    }

    @Test
    fun busyOutsideWindowIsIgnored() {
        val days = compute(listOf(busy(15, 6, 15, 7), busy(15, 19, 15, 20)))
        assertEquals(Duration.ZERO, days[0].busy)
        assertEquals(10.hours, days[0].net)
    }

    @Test
    fun fullyBookedDayHasZeroNet() {
        val days = compute(listOf(busy(16, 8, 16, 18)))
        assertEquals(10.hours, days[1].busy)
        assertEquals(Duration.ZERO, days[1].net)
    }

    @Test
    fun busySpanningDayBoundaryIsClippedPerDay() {
        // 15th 17:00 -> 16th 09:00: one hour inside day 0's window, one inside day 1's.
        val days = compute(listOf(busy(15, 17, 16, 9)))
        assertEquals(1.hours, days[0].busy)
        assertEquals(1.hours, days[1].busy)
        assertEquals(9.hours, days[0].net)
        assertEquals(9.hours, days[1].net)
    }

    @Test
    fun unionWindowSpansFirstStartToLastEnd() {
        val (start, end) = upcomingUnionWindow(from, 3, 8 * 60, 18 * 60, tz)
        assertEquals(at(15, 8), start)
        assertEquals(at(17, 18), end)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.UpcomingAvailabilityTest"`
Expected: FAIL — `calculateUpcomingAvailability` / `UpcomingDayAvailability` unresolved.

- [ ] **Step 3: Create the implementation**

Create `core/src/commonMain/kotlin/fr/dayview/app/UpcomingAvailability.kt`:

```kotlin
package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/** How many upcoming days the day-over screen summarises. */
const val UPCOMING_DAY_COUNT = 3

/**
 * Net availability of a single upcoming day. Carries the gross [window] and merged [busy]
 * (not only [net]) so a future per-day ring can render the busy share without a data change.
 */
data class UpcomingDayAvailability(
    val date: LocalDate,
    val window: Duration,
    val busy: Duration,
    val net: Duration,
)

/**
 * Net-available time for [dayCount] consecutive days starting at [fromDate]. Each day reuses the
 * global [startMinutes]/[endMinutes] window; [busy] is clipped to each day's window and merged so
 * overlapping events are not double-counted. net = (window - busy), floored at zero.
 */
fun calculateUpcomingAvailability(
    fromDate: LocalDate,
    dayCount: Int,
    startMinutes: Int,
    endMinutes: Int,
    busy: List<BusyInterval>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<UpcomingDayAvailability> {
    val fromEpoch = fromDate.toEpochDays()
    return (0 until dayCount).map { offset ->
        val date = LocalDate.fromEpochDays((fromEpoch + offset).toInt())
        val (start, end) = dayWindowFor(date, startMinutes, endMinutes, timeZone)
        val window = (end - start).coerceAtLeast(Duration.ZERO)
        val clipped = mergeBusyIntervals(
            busy.map {
                it.copy(
                    start = it.start.coerceIn(start, end),
                    end = it.end.coerceIn(start, end),
                )
            },
        )
        val busyTotal = clipped.fold(Duration.ZERO) { acc, interval -> acc + (interval.end - interval.start) }
        UpcomingDayAvailability(
            date = date,
            window = window,
            busy = busyTotal,
            net = (window - busyTotal).coerceAtLeast(Duration.ZERO),
        )
    }
}

/** The single instant span covering all [dayCount] upcoming day windows, for one calendar read. */
fun upcomingUnionWindow(
    fromDate: LocalDate,
    dayCount: Int,
    startMinutes: Int,
    endMinutes: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Instant, Instant> {
    val fromEpoch = fromDate.toEpochDays()
    val lastDate = LocalDate.fromEpochDays((fromEpoch + (dayCount - 1)).toInt())
    val start = dayWindowFor(fromDate, startMinutes, endMinutes, timeZone).first
    val end = dayWindowFor(lastDate, startMinutes, endMinutes, timeZone).second
    return start to end
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.UpcomingAvailabilityTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/UpcomingAvailability.kt \
        core/src/commonTest/kotlin/fr/dayview/app/UpcomingAvailabilityTest.kt
git commit -m "Add per-day upcoming availability calculation"
```

---

### Task 3: Controller state and setter (`:core`)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
  - Add two fields to `DayViewUiState` (after `busyIntervals`, ~line 52)
  - Add `upcomingDays` accessor (in the derived-accessors block, after `netTime`, ~line 120)
  - Add `updateUpcomingData` setter (after `updateNetTimeData`, ~line 737)
  - Add `import kotlinx.datetime.LocalDate`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/UpcomingDaysStateTest.kt` (create)

**Interfaces:**
- Consumes: `calculateUpcomingAvailability`, `UPCOMING_DAY_COUNT`, `UpcomingDayAvailability` (Task 2); `dayKeyOf`, `startOfLocalDay`, `isFocusBlock` (existing).
- Produces:
  - `DayViewUiState.upcomingBusyIntervals: List<BusyInterval>` (transient, default empty)
  - `DayViewUiState.upcomingFromDayKey: Long` (transient, default `-1L`)
  - `val DayViewUiState.upcomingDays: List<UpcomingDayAvailability>` (empty unless `isFinished && netTimeSettings.enabled && upcomingFromDayKey == dayKeyOf(today)+1`)
  - `fun DayViewController.updateUpcomingData(fromDayKey: Long, busyIntervals: List<BusyInterval>)`

- [ ] **Step 1: Write the failing test**

Create `core/src/commonTest/kotlin/fr/dayview/app/UpcomingDaysStateTest.kt`:

```kotlin
package fr.dayview.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class UpcomingDaysStateTest {
    // Anchor to local midnight, then +12h, so "finished" for a 00:00-00:30 window holds in any zone.
    private val now = startOfLocalDay(Instant.fromEpochMilliseconds(1_700_000_000_000L)) + 12.hours

    private fun controller(enabled: Boolean, endMinutes: Int) = DayViewController(
        DefaultDayPreferences,
        backgroundScope,
        initialSnapshot = DayPreferencesSnapshot(
            startMinutes = 0,
            endMinutes = endMinutes,
            netTimeSettings = NetTimeSettings(enabled = enabled),
        ),
        initialNow = now,
    )

    private fun tomorrowStart(): Instant {
        val fromDate = kotlinx.datetime.LocalDate.fromEpochDays((dayKeyOf(now) + 1).toInt())
        return dayWindowFor(fromDate, 0, 30).first
    }

    @Test
    fun emptyWhenNetTimeDisabledEvenIfFinishedAndFed() = runTest {
        val controller = controller(enabled = false, endMinutes = 30)
        controller.updateUpcomingData(dayKeyOf(now) + 1, emptyList())
        assertEquals(emptyList(), controller.state.upcomingDays)
    }

    @Test
    fun emptyWhenNotFinished() = runTest {
        // Full-day window: 12:00 local is mid-window, not finished.
        val controller = controller(enabled = true, endMinutes = 23 * 60 + 59)
        controller.updateUpcomingData(dayKeyOf(now) + 1, emptyList())
        assertEquals(emptyList(), controller.state.upcomingDays)
    }

    @Test
    fun emptyWhenNoDataFed() = runTest {
        val controller = controller(enabled = true, endMinutes = 30)
        assertTrue(controller.state.dayProgress.isFinished)
        assertEquals(emptyList(), controller.state.upcomingDays)
    }

    @Test
    fun populatedWhenFinishedEnabledAndFed() = runTest {
        val controller = controller(enabled = true, endMinutes = 30)
        val busy = BusyInterval(tomorrowStart() + 5.minutes, tomorrowStart() + 20.minutes)
        controller.updateUpcomingData(dayKeyOf(now) + 1, listOf(busy))
        val days = controller.state.upcomingDays
        assertEquals(UPCOMING_DAY_COUNT, days.size)
        // 30-min window minus a 15-min meeting -> 15 min net tomorrow.
        assertEquals(15.minutes, days[0].net)
    }

    @Test
    fun focusBlocksAreExcludedFromBusy() = runTest {
        val controller = controller(enabled = true, endMinutes = 30)
        val focus = BusyInterval(tomorrowStart() + 5.minutes, tomorrowStart() + 20.minutes, listOf("Focus"))
        controller.updateUpcomingData(dayKeyOf(now) + 1, listOf(focus))
        // Focus-titled event excluded -> full 30-min window available.
        assertEquals(30.minutes, controller.state.upcomingDays[0].net)
    }

    @Test
    fun staleFromDayKeyReadsAsEmpty() = runTest {
        val controller = controller(enabled = true, endMinutes = 30)
        controller.updateUpcomingData(dayKeyOf(now) + 5, emptyList()) // wrong day
        assertEquals(emptyList(), controller.state.upcomingDays)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.UpcomingDaysStateTest"`
Expected: FAIL — `updateUpcomingData` / `upcomingDays` unresolved.

- [ ] **Step 3: Add state fields**

In `DayViewController.kt`, add the import with the other imports (top of file):

```kotlin
import kotlinx.datetime.LocalDate
```

In the `DayViewUiState` data class, immediately after the `busyIntervals` field (line 52) add:

```kotlin
    val upcomingBusyIntervals: List<BusyInterval> = emptyList(),
    val upcomingFromDayKey: Long = -1L,
```

- [ ] **Step 4: Add the `upcomingDays` accessor**

In `DayViewUiState`, directly after the `netTime` accessor (ends line 120) add:

```kotlin

    /**
     * Net availability for the next [UPCOMING_DAY_COUNT] days, shown once the day is over.
     * Empty unless net time is enabled, today is finished, and a future busy layer for
     * tomorrow has been fetched (a stale key from a previous day reads as empty).
     */
    val upcomingDays: List<UpcomingDayAvailability>
        get() {
            if (!netTimeSettings.enabled || !dayProgress.isFinished) return emptyList()
            val tomorrowKey = dayKeyOf(dayNow) + 1
            if (upcomingFromDayKey != tomorrowKey) return emptyList()
            val fromDate = LocalDate.fromEpochDays(tomorrowKey.toInt())
            return calculateUpcomingAvailability(
                fromDate = fromDate,
                dayCount = UPCOMING_DAY_COUNT,
                startMinutes = startMinutes,
                endMinutes = endMinutes,
                busy = upcomingBusyIntervals.filterNot { it.isFocusBlock() },
            )
        }
```

- [ ] **Step 5: Add the `updateUpcomingData` setter**

In `DayViewController`, directly after `updateNetTimeData` (ends line 737) add:

```kotlin

    /**
     * Injects the future busy layer for the upcoming-days summary (off the UI thread).
     * Transient: never persisted (it is future data, irrelevant to history). Pass
     * [fromDayKey] = -1 with an empty list to clear (net time off, no permission, or read error).
     */
    fun updateUpcomingData(
        fromDayKey: Long,
        busyIntervals: List<BusyInterval>,
    ) {
        if (state.upcomingFromDayKey == fromDayKey && state.upcomingBusyIntervals == busyIntervals) return
        state = state.copy(
            upcomingFromDayKey = fromDayKey,
            upcomingBusyIntervals = busyIntervals,
        )
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.UpcomingDaysStateTest"`
Expected: PASS (6 tests).

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
        core/src/commonTest/kotlin/fr/dayview/app/UpcomingDaysStateTest.kt
git commit -m "Derive upcoming-days availability in controller state"
```

---

### Task 4: Fetch the future busy layer (`:shared`)

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/App.kt`
  - Add `import kotlinx.datetime.LocalDate`
  - Add a `LaunchedEffect` after the calendar `DisposableEffect` (~line 254)

**Interfaces:**
- Consumes: `upcomingUnionWindow`, `UPCOMING_DAY_COUNT` (Task 2), `controller.updateUpcomingData` (Task 3); `calendarSource`, `dayKeyOf`, `logError`, `calendarPermissionProbe`, `calendarChangeProbe` (existing in scope).
- Produces: no new public API — wires the fetch to the controller.

> This task is UI-effect glue over already-tested pure pieces (`upcomingUnionWindow`, `calculateUpcomingAvailability`). It has no unit test; correctness is confirmed by the build gate plus the manual check in Step 3.

- [ ] **Step 1: Add the import**

In `App.kt`, add with the other kotlinx.datetime imports:

```kotlin
import kotlinx.datetime.LocalDate
```

- [ ] **Step 2: Add the fetch effect**

In `App.kt`, immediately after the calendar-observer `DisposableEffect` block (closes at line 254, before `val onRequestCalendarAccess`), add:

```kotlin
                    // Once the day is over (and net time is on with permission), fetch the busy
                    // layer for the next few days so the day-over screen can show net availability.
                    // Keyed on the finished flag — which flips once at end-of-day — plus settings
                    // and the permission/change probes, so it does not re-run every minute tick.
                    val dayFinished = state.dayProgress.isFinished
                    LaunchedEffect(
                        dayFinished,
                        state.netTimeSettings,
                        state.startMinutes,
                        state.endMinutes,
                        calendarPermissionProbe,
                        calendarChangeProbe,
                    ) {
                        if (!dayFinished || !state.netTimeSettings.enabled) {
                            controller.updateUpcomingData(-1L, emptyList())
                            return@LaunchedEffect
                        }
                        val tomorrowKey = dayKeyOf(state.now) + 1
                        val busy = withContext(Dispatchers.Default) {
                            val granted = runCatching { calendarSource.hasPermission() }.getOrDefault(false)
                            if (!granted) {
                                null
                            } else {
                                val fromDate = LocalDate.fromEpochDays(tomorrowKey.toInt())
                                val (start, end) = upcomingUnionWindow(
                                    fromDate,
                                    UPCOMING_DAY_COUNT,
                                    state.startMinutes,
                                    state.endMinutes,
                                )
                                runCatching {
                                    calendarSource.busyIntervals(start, end, state.netTimeSettings.includedCalendarIds)
                                }.onFailure { logError("calendar", "upcoming busyIntervals read failed", it) }
                                    .getOrNull()
                            }
                        }
                        if (busy == null) {
                            controller.updateUpcomingData(-1L, emptyList())
                        } else {
                            controller.updateUpcomingData(tomorrowKey, busy)
                        }
                    }
```

- [ ] **Step 3: Build and manually verify**

Run: `./gradlew :shared:desktopMainClasses`
Expected: BUILD SUCCESSFUL (compiles).

Manual check (desktop): `./gradlew :shared:run`, enable Net Time in settings and grant calendar access, then set the day **end time** earlier than the current time in Day settings so the ring reads "DAY OVER". The "Next 3 days" rows appear below the ring; with net time toggled off they disappear.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "Fetch the upcoming-days busy layer when the day is over"
```

---

### Task 5: Render the "Next 3 days" section (`:shared`)

**Files:**
- Modify: `shared/src/commonMain/composeResources/values/strings.xml` (add two strings)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` (add one tag)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
  - Add `UpcomingDaysSection` composable
  - Add resource imports for the two new strings
  - Render it in the compact column (after `CountdownCircle`) and the wide left column (after `LongTermGoalPanel`)
- Test: `shared/src/desktopTest/kotlin/fr/dayview/app/UpcomingDaysSectionTest.kt` (create)

**Interfaces:**
- Consumes: `state.upcomingDays: List<UpcomingDayAvailability>` (Task 3); `weekdayLabel(dayKey: Long)` and `formatDurationHm` (existing).
- Produces:
  - `internal fun UpcomingDaysSection(days: List<UpcomingDayAvailability>, modifier: Modifier = Modifier)`
  - `DayViewTestTags.UpcomingDays = "upcomingDays"`

- [ ] **Step 1: Add string resources**

In `shared/src/commonMain/composeResources/values/strings.xml`, near the `weekday_*` block (after line 32), add:

```xml
    <!-- Day-over screen — upcoming days availability -->
    <string name="upcoming_title">Next 3 days</string>
    <string name="upcoming_tomorrow">Tomorrow</string>
```

- [ ] **Step 2: Add the test tag**

In `DayViewTestTags.kt`, add inside the object (e.g. after `Countdown`, line 12):

```kotlin
    const val UpcomingDays = "upcomingDays"
```

- [ ] **Step 3: Write the failing UI test**

Create `shared/src/desktopTest/kotlin/fr/dayview/app/UpcomingDaysSectionTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalTestApi::class)
class UpcomingDaysSectionTest {
    private fun day(dayOfMonth: Int, netHours: Long) = UpcomingDayAvailability(
        date = LocalDate(2026, 1, dayOfMonth),
        window = 10.hours,
        busy = (10 - netHours).hours,
        net = netHours.hours,
    )

    @Test
    fun rendersSectionWhenDaysPresent() = runComposeUiTest {
        setContent {
            DayViewTheme {
                UpcomingDaysSection(days = listOf(day(15, 7), day(16, 6), day(17, 8)))
            }
        }
        onNodeWithTag(DayViewTestTags.UpcomingDays).assertExists()
    }

    @Test
    fun rendersNothingWhenEmpty() = runComposeUiTest {
        setContent {
            DayViewTheme {
                UpcomingDaysSection(days = emptyList())
            }
        }
        onNodeWithTag(DayViewTestTags.UpcomingDays).assertDoesNotExist()
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.UpcomingDaysSectionTest"`
Expected: FAIL — `UpcomingDaysSection` unresolved.

- [ ] **Step 5: Add the composable and resource imports**

In `DayViewTodayScreen.kt`, add the two resource imports with the other `fr.dayview.app.generated.resources.*` imports:

```kotlin
import fr.dayview.app.generated.resources.upcoming_title
import fr.dayview.app.generated.resources.upcoming_tomorrow
```

Add the composable (place it near the other private Today helpers, e.g. just above `CompactTodayContent`, line 453):

```kotlin
@Composable
internal fun UpcomingDaysSection(
    days: List<UpcomingDayAvailability>,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return
    val colors = LocalDayViewColors.current
    Column(
        modifier = modifier.testTag(DayViewTestTags.UpcomingDays),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.upcoming_title),
            color = colors.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        days.forEachIndexed { index, day ->
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 280.dp).padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (index == 0) {
                        stringResource(Res.string.upcoming_tomorrow)
                    } else {
                        weekdayLabel(day.date.toEpochDays())
                    },
                    color = colors.muted,
                    fontSize = 14.sp,
                )
                Text(
                    text = formatDurationHm(day.net),
                    color = colors.mint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.UpcomingDaysSectionTest"`
Expected: PASS (2 tests).

- [ ] **Step 7: Render it on the Today screen**

In `DayViewScreen`, wide layout — in the left `Column`, after the `LongTermGoalPanel(...)` call (closes line 344), add:

```kotlin
                        if (state.upcomingDays.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            UpcomingDaysSection(state.upcomingDays)
                        }
```

Compact layout — after the `CountdownCircle(...)` call and before `Spacer(Modifier.height(6.dp))` + `TodayQuickActions` (line 384-385), add:

```kotlin
                if (state.upcomingDays.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    UpcomingDaysSection(state.upcomingDays)
                }
```

- [ ] **Step 8: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no ktlint or test failures. (If ktlint flags formatting, run `./gradlew ktlintFormat` and re-run.)

- [ ] **Step 9: Commit**

```bash
git add shared/src/commonMain/composeResources/values/strings.xml \
        shared/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        shared/src/desktopTest/kotlin/fr/dayview/app/UpcomingDaysSectionTest.kt
git commit -m "Show next-3-days availability on the day-over screen"
```

---

## Notes on design decisions

- **`formatDurationHm` output.** Rows render exactly like today's net-time figure — `"7 h 30"` (or `"20 min"` under an hour) — reusing the existing formatter, not the illustrative `7h 30m` from the spec mock.
- **Wide placement.** The section is rendered in the Today left column beneath the long-term-goal panel (rather than threaded into `SidePanel`'s parameter list) so it sits directly below the goal panel as the spec intends without expanding an unrelated composable's signature.
- **Transient future-busy layer.** `upcomingBusyIntervals` / `upcomingFromDayKey` are deliberately excluded from `toSnapshot`/`toUiState`/`withPersisted` — future data has no place in history and must not survive a cold launch on the following day.
- **Empty-calendar day.** A fetched day with no events still shows (net == full window); a fetch failure or missing permission clears the layer (`fromDayKey = -1`) so the section is hidden, distinct from "fetched, no events".
