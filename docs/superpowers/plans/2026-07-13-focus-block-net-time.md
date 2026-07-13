# Focus-block Net-Time Exclusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A busy calendar event whose title contains "focus" (case-insensitive) stays visible on the ring but no longer subtracts from the day's net available time.

**Architecture:** Add a pure `BusyInterval.isFocusBlock()` predicate in the shared module, then filter focus blocks out of the list handed to `calculateNetTime` at its single call site in `DayViewController.netTime`. The ring input (`busyBlockArcsState`) is left unfiltered, so focus arcs still render. Overlap de-duplication is unchanged: `calculateNetTime` already unions overlapping intervals via `mergeBusyIntervals`, and filtering happens before that union.

**Tech Stack:** Kotlin Multiplatform, kotlin.test, kotlinx-datetime, Gradle.

## Global Constraints

- ktlint is enforced: run `./gradlew ktlintCheck` before committing; every source line must pass.
- Full pre-commit verification: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Commit messages describe the change only — no reference to Claude/Anthropic/AI, no test-plan or verification section, no reference to `docs/superpowers/`.
- Shared logic lives in `composeApp/src/commonMain`; shared tests in `composeApp/src/commonTest`.

---

### Task 1: `isFocusBlock` predicate

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt` (add function after the `BusyInterval` data class, around line 19)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Consumes: `BusyInterval` (existing: `start`, `end`, `titles: List<String>`, `calendarId`).
- Produces: `fun BusyInterval.isFocusBlock(): Boolean` — true when any title contains the substring "focus" case-insensitively. Task 2 relies on this exact name and signature.

- [ ] **Step 1: Write the failing tests**

Add these tests to `CalendarNetTimeTest` (the `interval(start, end, vararg titles)` helper already exists at the top of the class):

```kotlin
    @Test
    fun isFocusBlockMatchesFocusSubstringCaseInsensitively() {
        assertEquals(true, interval(0, 100, "Focus").isFocusBlock())
        assertEquals(true, interval(0, 100, "FOCUS").isFocusBlock())
        assertEquals(true, interval(0, 100, "deep focus block").isFocusBlock())
    }

    @Test
    fun isFocusBlockIsFalseWithoutFocusSubstring() {
        assertEquals(false, interval(0, 100, "Standup").isFocusBlock())
        assertEquals(false, interval(0, 100).isFocusBlock()) // no titles at all
    }

    @Test
    fun isFocusBlockMatchesIfAnyTitleContainsFocus() {
        assertEquals(true, interval(0, 100, "Standup", "Focus time").isFocusBlock())
    }

    @Test
    fun filteringFocusBlocksLeavesOverlappingMeetingIntact() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val (start, end) = dayWindow(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // Focus block 14:00-16:00 overlaps a real meeting 15:00-16:00 (same window as the controller feeds).
        val busy = listOf(
            busyAt(noon + 2.hours, noon + 4.hours, "Deep Focus"),
            busyAt(noon + 3.hours, noon + 4.hours, "Atelier"),
        )
        val net = calculateNetTime(progress, noon, start, end, busy.filterNot { it.isFocusBlock() })
        // Only the meeting's own hour is subtracted; the overlapping focus span is gone.
        assertEquals(1.hours, net.busyRemaining)
        assertEquals((end - start) - 1.hours, net.netDay)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — compilation error, `isFocusBlock` is an unresolved reference.

- [ ] **Step 3: Add the predicate**

In `CalendarNetTime.kt`, immediately after the `BusyInterval` data class (currently ending at line 19), add:

```kotlin
/** A busy event reserved for goal work: any title contains "focus" (case-insensitive). */
fun BusyInterval.isFocusBlock(): Boolean = titles.any { it.contains("focus", ignoreCase = true) }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS (all `CalendarNetTimeTest` tests green).

- [ ] **Step 5: Run ktlint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL, no violations.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "Add isFocusBlock predicate for busy calendar events"
```

---

### Task 2: Exclude focus blocks from net time in the controller

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt:120-126` (the `netTime` computed property)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `BusyInterval.isFocusBlock()` from Task 1; existing `busyIntervalsToday`, `busyBlockArcsState`, `netTime`, `updateNetTimeData(hasPermission, busyIntervals, availableCalendars)`, and the `NetTime` fields `netDay` / `busyRemaining`.
- Produces: no new public API; changes the behavior of the existing `netTime` property.

- [ ] **Step 1: Write the failing test**

Add to `DayViewControllerTest` (the file already imports `LocalDateTime`, `TimeZone`, `toInstant`, `Instant`, `assertEquals`; add `import kotlin.time.Duration.Companion.hours` and `import kotlin.time.Duration.Companion.minutes` at the top of the file if not already present):

```kotlin
    @Test
    fun netTimeExcludesFocusBlocksButRingKeepsThem() {
        val zone = TimeZone.of("Europe/Paris")
        val noon = LocalDateTime(2026, 7, 11, 12, 0).toInstant(zone)
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                startMinutes = 8 * 60,
                endMinutes = 18 * 60,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
        )
        val controller = testController(preferences, noon.toEpochMilliseconds())

        // A real meeting 14:00-15:00 and a focus block 15:30-16:30, both this afternoon.
        controller.updateNetTimeData(
            hasPermission = true,
            busyIntervals = listOf(
                BusyInterval(noon + 2.hours, noon + 3.hours, listOf("Atelier"), "work"),
                BusyInterval(noon + 3.hours + 30.minutes, noon + 4.hours + 30.minutes, listOf("Deep Focus"), "work"),
            ),
            availableCalendars = listOf(CalendarInfo("work", "Work")),
        )

        val net = controller.state.netTime!!
        // Only the meeting counts: focus block is excluded from both the day total and remaining.
        assertEquals(1.hours, net.busyRemaining)
        assertEquals((10.hours) - 1.hours, net.netDay)
        // Both intervals still draw on the ring.
        assertEquals(2, controller.state.busyBlockArcsState.size)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `busyRemaining` is `2.hours` and `netDay` is `8.hours` because the focus block is still subtracted.

- [ ] **Step 3: Apply the filter at the call site**

In `DayViewController.kt`, change the `netTime` property (currently lines 120-126) to filter out focus blocks:

```kotlin
    val netTime: NetTime?
        get() = if (netTimeSettings.enabled) {
            val (start, end) = dayWindow
            calculateNetTime(dayProgress, dayNow, start, end, busyIntervalsToday.filterNot { it.isFocusBlock() })
        } else {
            null
        }
```

Leave `busyBlockArcsState` (lines 112-118) untouched.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 5: Full verification and ktlint**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, all tests green, no ktlint violations, no stderr.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Exclude focus-block events from net available time"
```

---

## Notes on overlap ("not counted twice")

No new code is required for overlap de-duplication. `calculateNetTime` runs its input through `mergeBusyIntervals`, which unions overlapping intervals, and the existing test `netTimeCountsCrossCalendarOverlapOnce` in `CalendarNetTimeTest` guards this. Because Task 2 filters focus blocks *before* that union, two overlapping real meetings are still counted once, and a focus block overlapping a meeting simply leaves the meeting's span intact.
