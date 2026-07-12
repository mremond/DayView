# Clean Focus Sessions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Count each day's *serious* focus sessions (completed, no off-goal drift, no overlapping detour) and a daily streak, shown as a purely-positive glanceable line on the Today screen — a low-stakes reward that makes starting a session feel safe.

**Architecture:** All decision logic lives in pure functions and one small stateful tracker in a new `CleanFocusSessions.kt` (common), fully unit-testable. The `DayViewController` evaluates cleanliness inside the existing `closePomodoro(outcome)` hook and owns the persisted ledger. Off-goal drift is measured only on desktop (the desktop loop already classifies the frontmost app) and bridged into the app exactly like `focusPresenceIntervals`; Android's off-goal is always zero, so the feature degrades to "completed + no detour" there.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, `kotlin.time` (`Instant`/`Duration`), androidx DataStore preferences, kotlin.test.

## Global Constraints

- ktlint is enforced. Run `./gradlew ktlintCheck` (or `ktlintFormat`) before every commit.
- Full pre-commit gate: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — must pass with no errors or stderr.
- JDK 21 required (Robolectric needs it for compileSdk 36).
- Domain time is `kotlin.time.Instant`/`Duration`. Epoch millis appear only at the serialization boundary.
- User-facing strings are externalized to Compose resources, both `values/strings.xml` (default/English) and `values-fr/strings.xml`. Single `%` in format strings (e.g. `%1$d`), never `%%`.
- Compose UI tests (desktopTest): NEVER assert `stringResource` text (unresolved in `runComposeUiTest` on CI). Assert on test tags or seeded data. `assertExists()` is a member — no import needed.
- Commit messages describe the change only, in English. NEVER add any reference to Claude/Anthropic/an AI assistant, a `Co-Authored-By` trailer, a test plan, or a reference to these planning docs.

---

### Task 1: Session-cleanliness evaluation (pure)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt`

**Interfaces:**
- Consumes: `DetourEpisode` (from `Detours.kt`), `FocusClosureOutcome` (from `Pomodoro.kt`), `OnGoalState` (from `OnGoalState.kt`).
- Produces:
  - `val DEFAULT_OFF_GOAL_TOLERANCE: Duration` (= `30.seconds`)
  - `data class FocusSessionWindow(val start: Instant, val end: Instant)`
  - `fun evaluateSessionClean(window: FocusSessionWindow, offGoalDuring: Duration, detours: List<DetourEpisode>, outcome: FocusClosureOutcome, tolerance: Duration = DEFAULT_OFF_GOAL_TOLERANCE): Boolean`

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun at(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

class CleanFocusSessionsTest {
    private val window = FocusSessionWindow(at(1_000_000L), at(1_000_000L + 25 * 60_000L))

    @Test
    fun completedWithNoDriftAndNoDetourIsClean() {
        assertTrue(
            evaluateSessionClean(window, offGoalDuring = 0.seconds, detours = emptyList(), outcome = FocusClosureOutcome.COMPLETED),
        )
    }

    @Test
    fun progressedNeverCounts() {
        assertFalse(
            evaluateSessionClean(window, offGoalDuring = 0.seconds, detours = emptyList(), outcome = FocusClosureOutcome.PROGRESSED),
        )
    }

    @Test
    fun toResumeNeverCounts() {
        assertFalse(
            evaluateSessionClean(window, offGoalDuring = 0.seconds, detours = emptyList(), outcome = FocusClosureOutcome.TO_RESUME),
        )
    }

    @Test
    fun offGoalAtToleranceIsCleanButOverIsNot() {
        assertTrue(
            evaluateSessionClean(window, offGoalDuring = 30.seconds, detours = emptyList(), outcome = FocusClosureOutcome.COMPLETED),
        )
        assertFalse(
            evaluateSessionClean(window, offGoalDuring = 31.seconds, detours = emptyList(), outcome = FocusClosureOutcome.COMPLETED),
        )
    }

    @Test
    fun overlappingDetourBlocksButAdjacentAndOutsideDoNot() {
        val overlapping = DetourEpisode(window.start + 5.minutes, window.start + 10.minutes, "call")
        val touchingEnd = DetourEpisode(window.end, window.end + 5.minutes, "call")
        val outside = DetourEpisode(window.end + 5.minutes, window.end + 10.minutes, "call")
        assertFalse(
            evaluateSessionClean(window, 0.seconds, listOf(overlapping), FocusClosureOutcome.COMPLETED),
        )
        assertTrue(
            evaluateSessionClean(window, 0.seconds, listOf(touchingEnd, outside), FocusClosureOutcome.COMPLETED),
        )
    }

    @Test
    fun defaultToleranceIsThirtySeconds() {
        assertEquals(30.seconds, DEFAULT_OFF_GOAL_TOLERANCE)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CleanFocusSessionsTest"`
Expected: FAIL — unresolved reference `FocusSessionWindow` / `evaluateSessionClean` / `DEFAULT_OFF_GOAL_TOLERANCE`.

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt`:

```kotlin
package fr.dayview.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Off-goal foreground time tolerated inside a focus session before it stops counting as serious. */
val DEFAULT_OFF_GOAL_TOLERANCE: Duration = 30.seconds

/** The active span of a focus session: `[start, end]` where `end = start + duration`. */
data class FocusSessionWindow(val start: Instant, val end: Instant)

/**
 * A focus session is "serious" iff it ran to term (COMPLETED — not PROGRESSED or
 * TO_RESUME), off-goal foreground time within the window stayed at or below [tolerance],
 * and no declared detour overlaps the window. Detours that merely touch a window edge do
 * not overlap.
 */
fun evaluateSessionClean(
    window: FocusSessionWindow,
    offGoalDuring: Duration,
    detours: List<DetourEpisode>,
    outcome: FocusClosureOutcome,
    tolerance: Duration = DEFAULT_OFF_GOAL_TOLERANCE,
): Boolean {
    if (outcome != FocusClosureOutcome.COMPLETED) return false
    if (offGoalDuring > tolerance) return false
    return detours.none { it.start < window.end && it.end > window.start }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CleanFocusSessionsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt composeApp/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt
git commit -m "Add clean-session evaluation for focus sessions"
```

---

### Task 2: Off-goal accumulation tracker

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt`

**Interfaces:**
- Consumes: `OnGoalState`.
- Produces: `class SessionCleanlinessTracker` with `fun observe(now: Instant, sessionEnd: Instant?, state: OnGoalState): Duration` and `val offGoalDuration: Duration`. It accumulates OFF_GOAL time for the session identified by `sessionEnd`, resetting whenever `sessionEnd` changes.

- [ ] **Step 1: Write the failing test**

Append these tests to `CleanFocusSessionsTest.kt` (inside the class):

```kotlin
    @Test
    fun trackerAccumulatesOnlyOffGoalTime() {
        val tracker = SessionCleanlinessTracker()
        val end = at(2_000_000L)
        // First observation seeds lastObserved without adding time.
        tracker.observe(at(0L), end, OnGoalState.ON_GOAL)
        // 10s off-goal counts.
        tracker.observe(at(10_000L), end, OnGoalState.OFF_GOAL)
        // 5s on-goal does not.
        tracker.observe(at(15_000L), end, OnGoalState.ON_GOAL)
        // 5s neutral does not.
        tracker.observe(at(20_000L), end, OnGoalState.NEUTRAL)
        // 3s off-goal counts.
        val total = tracker.observe(at(23_000L), end, OnGoalState.OFF_GOAL)
        assertEquals(13.seconds, total)
        assertEquals(13.seconds, tracker.offGoalDuration)
    }

    @Test
    fun trackerResetsWhenSessionChanges() {
        val tracker = SessionCleanlinessTracker()
        tracker.observe(at(0L), at(1L), OnGoalState.ON_GOAL)
        tracker.observe(at(10_000L), at(1L), OnGoalState.OFF_GOAL)
        assertEquals(10.seconds, tracker.offGoalDuration)
        // New session end -> reset.
        tracker.observe(at(20_000L), at(999L), OnGoalState.OFF_GOAL)
        assertEquals(0.seconds, tracker.offGoalDuration)
    }

    @Test
    fun trackerIgnoresOffGoalWhenNoSession() {
        val tracker = SessionCleanlinessTracker()
        tracker.observe(at(0L), null, OnGoalState.OFF_GOAL)
        tracker.observe(at(10_000L), null, OnGoalState.OFF_GOAL)
        assertEquals(0.seconds, tracker.offGoalDuration)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CleanFocusSessionsTest"`
Expected: FAIL — unresolved reference `SessionCleanlinessTracker`.

- [ ] **Step 3: Write minimal implementation**

Append to `CleanFocusSessions.kt`:

```kotlin
/**
 * Accumulates OFF_GOAL foreground time for the current focus session. The session is
 * identified by its [sessionEnd]; when that changes (a new session starts, or the session
 * is cleared to null), the accumulator resets. NEUTRAL and ON_GOAL ticks add nothing, and
 * nothing accrues while there is no session. Fed once per tick from the desktop loop.
 */
class SessionCleanlinessTracker {
    private var sessionEnd: Instant? = null
    private var lastObserved: Instant? = null
    private var accumulated: Duration = Duration.ZERO

    val offGoalDuration: Duration get() = accumulated

    fun observe(
        now: Instant,
        sessionEnd: Instant?,
        state: OnGoalState,
    ): Duration {
        if (sessionEnd != this.sessionEnd) {
            this.sessionEnd = sessionEnd
            lastObserved = null
            accumulated = Duration.ZERO
        }
        val previous = lastObserved
        lastObserved = now
        if (sessionEnd != null && state == OnGoalState.OFF_GOAL && previous != null && now > previous) {
            accumulated += now - previous
        }
        return accumulated
    }
}
```

Add the import at the top of the file if the IDE flags it (`Duration` is already imported).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CleanFocusSessionsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt composeApp/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt
git commit -m "Add per-session off-goal accumulation tracker"
```

---

### Task 3: Daily ledger and streak transitions

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt`

**Interfaces:**
- Produces:
  - `data class CleanSessionLedger(val dayKey: Long = -1L, val cleanToday: Int = 0, val streakDays: Int = 0, val streakLastDayKey: Long = -1L)`
  - `fun rollOver(ledger: CleanSessionLedger, dayKey: Long): CleanSessionLedger`
  - `fun registerCleanSession(ledger: CleanSessionLedger, dayKey: Long): CleanSessionLedger`
  - `fun displayedStreak(ledger: CleanSessionLedger, dayKey: Long): Int`

- [ ] **Step 1: Write the failing test**

Append to `CleanFocusSessionsTest.kt`:

```kotlin
    @Test
    fun firstCleanSessionStartsCountAndStreak() {
        val after = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        assertEquals(100L, after.dayKey)
        assertEquals(1, after.cleanToday)
        assertEquals(1, after.streakDays)
        assertEquals(100L, after.streakLastDayKey)
    }

    @Test
    fun secondCleanSessionSameDayIncrementsCountNotStreak() {
        val first = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        val second = registerCleanSession(first, dayKey = 100L)
        assertEquals(2, second.cleanToday)
        assertEquals(1, second.streakDays)
    }

    @Test
    fun consecutiveDayExtendsStreakAndResetsCount() {
        val day100 = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        val day101 = registerCleanSession(day100, dayKey = 101L)
        assertEquals(1, day101.cleanToday)
        assertEquals(2, day101.streakDays)
        assertEquals(101L, day101.streakLastDayKey)
    }

    @Test
    fun gapRestartsStreakAtOne() {
        val day100 = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        val day103 = registerCleanSession(day100, dayKey = 103L)
        assertEquals(1, day103.streakDays)
    }

    @Test
    fun rollOverResetsCountButKeepsStreakState() {
        val day100 = registerCleanSession(CleanSessionLedger(), dayKey = 100L)
        val rolled = rollOver(day100, dayKey = 101L)
        assertEquals(101L, rolled.dayKey)
        assertEquals(0, rolled.cleanToday)
        assertEquals(1, rolled.streakDays)
        assertEquals(100L, rolled.streakLastDayKey)
    }

    @Test
    fun displayedStreakHidesADeadStreak() {
        val ledger = CleanSessionLedger(dayKey = 100L, cleanToday = 0, streakDays = 3, streakLastDayKey = 100L)
        // Same day and next day: still alive.
        assertEquals(3, displayedStreak(ledger, dayKey = 100L))
        assertEquals(3, displayedStreak(ledger, dayKey = 101L))
        // A day was missed with nothing yet today: shown as 0.
        assertEquals(0, displayedStreak(ledger, dayKey = 102L))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CleanFocusSessionsTest"`
Expected: FAIL — unresolved reference `CleanSessionLedger` / `registerCleanSession` / `rollOver` / `displayedStreak`.

- [ ] **Step 3: Write minimal implementation**

Append to `CleanFocusSessions.kt`:

```kotlin
/**
 * Day-scoped tally of serious sessions plus streak state. Persisted; the empty default
 * (all sentinels) is what a fresh install reads back.
 */
data class CleanSessionLedger(
    val dayKey: Long = -1L,
    val cleanToday: Int = 0,
    val streakDays: Int = 0,
    val streakLastDayKey: Long = -1L,
)

/** Reset today's count when the day changed; leave streak state untouched (broken lazily). */
fun rollOver(ledger: CleanSessionLedger, dayKey: Long): CleanSessionLedger =
    if (ledger.dayKey == dayKey) ledger else ledger.copy(dayKey = dayKey, cleanToday = 0)

/**
 * Record one serious session on [dayKey]: increment today's count and, if it is the day's
 * first, extend the streak (consecutive day) or restart it at 1 (after a gap).
 */
fun registerCleanSession(ledger: CleanSessionLedger, dayKey: Long): CleanSessionLedger {
    val rolled = rollOver(ledger, dayKey)
    val firstToday = rolled.cleanToday == 0
    val streakDays = when {
        !firstToday -> rolled.streakDays
        rolled.streakLastDayKey == dayKey - 1 -> rolled.streakDays + 1
        else -> 1
    }
    return rolled.copy(
        cleanToday = rolled.cleanToday + 1,
        streakDays = streakDays,
        streakLastDayKey = dayKey,
    )
}

/** Streak to show today: the stored value only while still alive, else 0 (never stale). */
fun displayedStreak(ledger: CleanSessionLedger, dayKey: Long): Int =
    if (ledger.streakLastDayKey >= dayKey - 1) ledger.streakDays else 0
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CleanFocusSessionsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt composeApp/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt
git commit -m "Add clean-session daily ledger and streak transitions"
```

---

### Task 4: Persist the ledger in preferences

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt` (add field to `DayPreferencesSnapshot`, ~line 24)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt` (keys ~line 36, key vals ~line 61, persist ~line 91, restore ~line 133)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`

**Interfaces:**
- Consumes: `CleanSessionLedger` (Task 3).
- Produces: `DayPreferencesSnapshot.cleanSessions: CleanSessionLedger`, round-tripped through `DayPreferencesStore`.

- [ ] **Step 1: Write the failing test**

Add to `DayPreferencesStoreTest.kt` a test mirroring the existing round-trip style (open the file and follow the pattern used for detours: build a store, persist a snapshot, read it back, assert equality). Insert this test method inside the test class:

```kotlin
    @Test
    fun persistsAndRestoresCleanSessionLedger() = runTest {
        withStore { store ->
            val ledger = CleanSessionLedger(dayKey = 42L, cleanToday = 3, streakDays = 5, streakLastDayKey = 42L)
            store.persist(DayPreferencesSnapshot(cleanSessions = ledger))
            assertEquals(ledger, store.snapshots.first().cleanSessions)
        }
    }

    @Test
    fun absentLedgerKeysRestoreEmptyLedger() = runTest {
        withStore { store ->
            assertEquals(CleanSessionLedger(), store.snapshots.first().cleanSessions)
        }
    }
```

If the existing test file uses a different harness than `withStore { ... }` / `runTest`, match whatever `DayPreferencesStoreTest.kt` already uses (e.g. a `createStore()` helper). Keep `assertEquals(ledger, ...)` and the empty-default assertion; adapt only the setup wrapper to the file's convention. Ensure `import kotlinx.coroutines.flow.first` and `import kotlin.test.assertEquals` are present.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: FAIL — `cleanSessions` is not a member of `DayPreferencesSnapshot`.

- [ ] **Step 3: Write minimal implementation**

In `DayPreferences.kt`, add the field to `DayPreferencesSnapshot` (after `themeMode`):

```kotlin
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cleanSessions: CleanSessionLedger = CleanSessionLedger(),
)
```

In `DayPreferencesStore.kt`, add to `DayPreferenceKeys` (after `THEME_MODE`):

```kotlin
    const val CLEAN_SESSIONS_DAY = "clean_sessions_day"
    const val CLEAN_SESSIONS_TODAY = "clean_sessions_today"
    const val CLEAN_STREAK_DAYS = "clean_streak_days"
    const val CLEAN_STREAK_LAST_DAY = "clean_streak_last_day"
```

Add the key vals (after `themeModeKey`):

```kotlin
private val cleanSessionsDayKey = longPreferencesKey(DayPreferenceKeys.CLEAN_SESSIONS_DAY)
private val cleanSessionsTodayKey = intPreferencesKey(DayPreferenceKeys.CLEAN_SESSIONS_TODAY)
private val cleanStreakDaysKey = intPreferencesKey(DayPreferenceKeys.CLEAN_STREAK_DAYS)
private val cleanStreakLastDayKey = longPreferencesKey(DayPreferenceKeys.CLEAN_STREAK_LAST_DAY)
```

In `persist(...)`, after the `themeModeKey` line:

```kotlin
            prefs[cleanSessionsDayKey] = snapshot.cleanSessions.dayKey
            prefs[cleanSessionsTodayKey] = snapshot.cleanSessions.cleanToday
            prefs[cleanStreakDaysKey] = snapshot.cleanSessions.streakDays
            prefs[cleanStreakLastDayKey] = snapshot.cleanSessions.streakLastDayKey
```

In `toSnapshot()`, after the `themeMode = ...` line:

```kotlin
        cleanSessions = CleanSessionLedger(
            dayKey = this[cleanSessionsDayKey] ?: defaults.cleanSessions.dayKey,
            cleanToday = this[cleanSessionsTodayKey] ?: defaults.cleanSessions.cleanToday,
            streakDays = this[cleanStreakDaysKey] ?: defaults.cleanSessions.streakDays,
            streakLastDayKey = this[cleanStreakLastDayKey] ?: defaults.cleanSessions.streakLastDayKey,
        ),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt
git commit -m "Persist the clean-session ledger in preferences"
```

---

### Task 5: Wire evaluation into the controller

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (`DayViewUiState` ~line 51, `closePomodoro` ~line 270, add `setSessionOffGoal`, `toSnapshot`/`toUiState`/`withPersisted`/`coerced`)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `FocusSessionWindow`, `evaluateSessionClean`, `CleanSessionLedger`, `registerCleanSession`, `rollOver`, `displayedStreak` (Tasks 1/3); `dayKeyOf` (Detours.kt); `DayPreferencesSnapshot.cleanSessions` (Task 4).
- Produces: `DayViewUiState.cleanSessions: CleanSessionLedger`, `DayViewUiState.sessionOffGoal: Duration`, derived `DayViewUiState.cleanSessionsToday: Int` and `DayViewUiState.cleanStreakDays: Int`, and `DayViewController.setSessionOffGoal(duration: Duration)`.

- [ ] **Step 1: Write the failing test**

Append to `DayViewControllerTest.kt`. Note `dayKeyOf(t(nowMillis))` is 0 for small millis (epoch day 0), so seeded and computed day keys agree.

```kotlin
    @Test
    fun completedSessionWithoutDriftRegistersACleanSession() {
        val now = 60L * 60_000L // 1h after epoch, still day 0
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                focusIntention = "Réviser le chapitre 3",
                pomodoroMinutes = 25,
                pomodoroEnd = t(now),
            ),
        )
        val controller = testController(preferences, now)

        controller.closePomodoro(FocusClosureOutcome.COMPLETED)

        assertEquals(1, controller.state.cleanSessionsToday)
        assertEquals(1, controller.state.cleanStreakDays)
        assertEquals(1, preferences.current.cleanSessions.cleanToday)
    }

    @Test
    fun progressedSessionDoesNotRegister() {
        val now = 60L * 60_000L
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(pomodoroMinutes = 25, pomodoroEnd = t(now)),
        )
        val controller = testController(preferences, now)

        controller.closePomodoro(FocusClosureOutcome.PROGRESSED)

        assertEquals(0, controller.state.cleanSessionsToday)
    }

    @Test
    fun overlappingDetourBlocksTheCleanSession() {
        val now = 60L * 60_000L
        // A 4-minute detour (24..20 min before now) sits fully inside the 25-minute window.
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                pomodoroMinutes = 25,
                pomodoroEnd = t(now),
                detoursDayKey = dayKeyOf(t(now)),
                detours = listOf(DetourEpisode(t(now - 24 * 60_000L), t(now - 20 * 60_000L), "call")),
            ),
        )
        val controller = testController(preferences, now)

        controller.closePomodoro(FocusClosureOutcome.COMPLETED)

        assertEquals(0, controller.state.cleanSessionsToday)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `cleanSessionsToday` / `cleanStreakDays` are not members of `DayViewUiState`.

- [ ] **Step 3: Write minimal implementation**

In `DayViewController.kt`, add two fields to `DayViewUiState` (after `themeMode`):

```kotlin
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cleanSessions: CleanSessionLedger = CleanSessionLedger(),
    val sessionOffGoal: Duration = Duration.ZERO,
```

Add the derived getters at the end of `DayViewUiState` (after `detoursTotalToday`):

```kotlin
    val cleanSessionsToday: Int
        get() = if (cleanSessions.dayKey == dayKeyOf(dayNow)) cleanSessions.cleanToday else 0

    val cleanStreakDays: Int
        get() = displayedStreak(cleanSessions, dayKeyOf(dayNow))
```

Replace `closePomodoro` with:

```kotlin
    fun closePomodoro(outcome: FocusClosureOutcome) {
        val updatedIntention = focusIntentionAfterClosure(state.focusIntention, outcome)
        val dayKey = dayKeyOf(state.now)
        val ledger = state.pomodoroEnd?.let { end ->
            val window = FocusSessionWindow(end - state.pomodoroMinutes.minutes, end)
            val clean = evaluateSessionClean(window, state.sessionOffGoal, state.detoursToday, outcome)
            if (clean) registerCleanSession(state.cleanSessions, dayKey) else rollOver(state.cleanSessions, dayKey)
        } ?: state.cleanSessions
        // Single atomic persist of the whole snapshot.
        state = state.copy(
            pomodoroEnd = null,
            focusIntention = updatedIntention,
            lastFocusClosure = outcome,
            cleanSessions = ledger,
            sessionOffGoal = Duration.ZERO,
        )
        persistState()
    }
```

Add a setter near `setFocusPresenceIntervals`:

```kotlin
    fun setSessionOffGoal(duration: Duration) {
        state = state.copy(sessionOffGoal = duration)
    }
```

In `DayViewUiState.toSnapshot()`, add `cleanSessions = cleanSessions,` (after `themeMode = themeMode,`).

In `DayPreferencesSnapshot.coerced()`, guard the counts (inside the `copy(...)`):

```kotlin
        cleanSessions = cleanSessions.copy(
            cleanToday = cleanSessions.cleanToday.coerceAtLeast(0),
            streakDays = cleanSessions.streakDays.coerceAtLeast(0),
        ),
```

In `DayPreferencesSnapshot.toUiState()`, add `cleanSessions = safe.cleanSessions,` (after `themeMode = safe.themeMode,`). `sessionOffGoal` is transient — do not set it here (defaults to `Duration.ZERO`).

In `DayViewUiState.withPersisted()`, add `cleanSessions = safe.cleanSessions,` (after `themeMode = safe.themeMode,`). Leave `sessionOffGoal` out of the copy (transient, preserved). Update the trailing comment to include `sessionOffGoal` in the preserved-transient list.

Ensure `Duration` is imported (it is, via `kotlin.time.Duration`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Register clean focus sessions when a session closes"
```

---

### Task 6: Today-screen display of clean sessions and streak

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`CountdownCircle` signature ~line 669, totals block ~line 943, call sites ~line 232 and ~line 286)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/TodayScreenTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.cleanSessionsToday`, `DayViewUiState.cleanStreakDays` (Task 5).
- Produces: `DayViewTestTags.CleanSessions`; a rendered line tagged with it when `cleanSessionsToday > 0`.

- [ ] **Step 1: Write the failing test**

Add to `TodayScreenTest.kt`:

```kotlin
    @Test
    fun rendersCleanSessionsLineWhenPresent() = runComposeUiTest {
        val now = midWindowNow()
        val dayKey = dayKeyOf(now)
        val snapshot = DayPreferencesSnapshot(
            cleanSessions = CleanSessionLedger(dayKey = dayKey, cleanToday = 3, streakDays = 5, streakLastDayKey = dayKey),
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.CleanSessions).assertExists()
    }
```

Ensure the file imports what it needs: `dayKeyOf` and `CleanSessionLedger` are in the same package (no import), `midWindowNow`/`seededController`/`WideDayView`/`noopDayViewActions` come from `UiTestSupport.kt`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.TodayScreenTest"`
Expected: FAIL — `CleanSessions` is not a member of `DayViewTestTags` (and the node would not exist).

- [ ] **Step 3: Write minimal implementation**

In `DayViewTestTags.kt`, add (after `Countdown`):

```kotlin
    const val CleanSessions = "cleanSessions"
```

In `values/strings.xml`, add near the focus/detour strings:

```xml
    <string name="clean_sessions_today">Serious %1$d</string>
    <string name="clean_streak">Streak %1$d d</string>
```

In `values-fr/strings.xml`, add the matching keys:

```xml
    <string name="clean_sessions_today">Sérieux %1$d</string>
    <string name="clean_streak">Série %1$d j</string>
```

In `DayViewTodayScreen.kt`, add two parameters to `CountdownCircle` (after `detoursTotal`):

```kotlin
    detoursTotal: Duration = Duration.ZERO,
    cleanSessionsToday: Int = 0,
    streakDays: Int = 0,
```

Add the resource imports next to the existing `focused_today` / `detours_today` imports:

```kotlin
import fr.dayview.app.generated.resources.clean_sessions_today
import fr.dayview.app.generated.resources.clean_streak
```

In the totals block, after the `detoursTotal > Duration.ZERO` `if` (ends ~line 952), add:

```kotlin
                        if (cleanSessionsToday > 0) {
                            Spacer(Modifier.height(6.dp))
                            val label = if (streakDays > 0) {
                                stringResource(Res.string.clean_sessions_today, cleanSessionsToday) +
                                    " · " + stringResource(Res.string.clean_streak, streakDays)
                            } else {
                                stringResource(Res.string.clean_sessions_today, cleanSessionsToday)
                            }
                            Text(
                                label,
                                color = colors.mint,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = .5.sp,
                                modifier = Modifier.testTag(DayViewTestTags.CleanSessions),
                            )
                        }
```

At BOTH `CountdownCircle(...)` call sites (the wide layout ~line 232 and the compact layout ~line 286), add the two arguments next to the existing `detoursTotal = state.detoursTotalToday,`:

```kotlin
                            detoursTotal = state.detoursTotalToday,
                            cleanSessionsToday = state.cleanSessionsToday,
                            streakDays = state.cleanStreakDays,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.TodayScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-fr/strings.xml composeApp/src/desktopTest/kotlin/fr/dayview/app/TodayScreenTest.kt
git commit -m "Show serious focus sessions and streak on the Today screen"
```

---

### Task 7: Feed off-goal drift from the desktop loop

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt` (presence loop ~line 82–170, `DayViewApp(...)` call)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (`DayViewApp` signature ~line 30–42, bridge effect ~line 69–71)

**Interfaces:**
- Consumes: `SessionCleanlinessTracker` (Task 2), `DayViewController.setSessionOffGoal` (Task 5).
- Produces: `DayViewApp(..., sessionOffGoal: Duration = Duration.ZERO)`; the desktop loop computing and passing that value.

This is glue between two already-tested units (the tracker and the controller setter); it has no independently unit-testable seam under the `runComposeUiTest` constraints, so it is verified by building both targets and running the desktop app. Android inherits the `Duration.ZERO` default and is unaffected.

- [ ] **Step 1: Add the `sessionOffGoal` parameter to `DayViewApp` and bridge it**

In `App.kt`, add the parameter to the `DayViewApp` composable signature (next to `focusPresenceIntervals`):

```kotlin
    focusPresenceIntervals: List<FocusPresenceInterval> = emptyList(),
    sessionOffGoal: Duration = Duration.ZERO,
```

Add a bridge effect right after the existing `focusPresenceIntervals` effect (~line 71):

```kotlin
            LaunchedEffect(sessionOffGoal) {
                controller.setSessionOffGoal(sessionOffGoal)
            }
```

Ensure `import kotlin.time.Duration` is present in `App.kt`.

- [ ] **Step 2: Accumulate off-goal in the desktop loop and pass it in**

In `Main.kt`, add tracker + state near the `presenceAccumulator` (~line 82–90):

```kotlin
    val cleanlinessTracker = remember { SessionCleanlinessTracker() }
    var sessionOffGoal by remember { mutableStateOf(Duration.ZERO) }
```

Inside the loop, right after `val classification = classifyFrontmost(frontmostBundleId, onGoalBundleIds)` (~line 155), add:

```kotlin
            sessionOffGoal = cleanlinessTracker.observe(currentNow, currentPomodoroEnd, classification)
```

Pass it to the app at the `DayViewApp(...)` call (next to where `focusPresenceIntervals` is passed):

```kotlin
                sessionOffGoal = sessionOffGoal,
```

Ensure `import kotlin.time.Duration` and the `androidx.compose.runtime` imports for `mutableStateOf`/`remember`/`getValue`/`setValue` are present (most already are in `Main.kt`).

- [ ] **Step 3: Build both targets to verify wiring compiles**

Run: `./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL (Android compiles with the default `Duration.ZERO`; desktop compiles with the bridge).

- [ ] **Step 4: Verify behavior by running the desktop app**

Run: `./gradlew :composeApp:run`
Then: set a short focus intention, start a 5-minute Pomodoro, stay on an on-goal app until it completes, close it as "Completed", and confirm the Today screen shows "Serious 1". Start another, switch to an off-goal app for over a minute, complete and close it — confirm the count does NOT increase. (No automated assertion; this is manual verification of the desktop-only drift path.)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "Feed desktop off-goal drift into clean-session evaluation"
```

---

### Task 8: Full gate and branch wrap-up

**Files:** none (verification only).

- [ ] **Step 1: Run the full pre-commit gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint violations, all tests green, no stderr.

- [ ] **Step 2: Fix any ktlint or test issues**

If `ktlintCheck` reports violations, run `./gradlew ktlintFormat`, re-run the gate, and amend/commit the formatting fix:

```bash
git add -A
git commit -m "Apply ktlint formatting to clean-session sources"
```

(Skip this commit if there was nothing to format.)

- [ ] **Step 3: Confirm the branch is ready**

Run: `git log --oneline origin/main..HEAD`
Expected: only the clean-focus-session commits from this plan, nothing unrelated. The feature is ready for the finishing-a-development-branch flow (PR).

---

## Self-Review Notes

- **Spec coverage:** evaluation rule (Task 1), off-goal tracker (Task 2), ledger + streak + `displayedStreak` (Task 3), persistence via typed keys (Task 4), controller integration on `closePomodoro` + rollover + derived getters (Task 5), only-positive Today-screen line with streak (Task 6), desktop-only off-goal bridge with graceful Android degradation (Task 7), full gate (Task 8). All spec sections map to a task.
- **No settings surface, no `PROGRESSED`, no Draftline** — all correctly out of scope per the spec.
- **Type consistency:** `CleanSessionLedger`, `FocusSessionWindow`, `evaluateSessionClean`, `registerCleanSession`, `rollOver`, `displayedStreak`, `SessionCleanlinessTracker.observe`, `setSessionOffGoal`, `cleanSessionsToday`, `cleanStreakDays`, and `DayViewTestTags.CleanSessions` are used with identical names/signatures across tasks.
