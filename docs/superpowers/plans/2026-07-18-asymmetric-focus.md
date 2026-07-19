# Asymmetric Focus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Focus lifecycle asymmetric — starting is free (no mandatory intention, a 5-min preset), leaving before term requires naming a detour, and time past the term counts as overtime until a conscious closure.

**Architecture:** All behaviour changes live in `:core` pure functions and `DayViewController`; the Compose UI in `:shared` gains an extended closure sheet and a preset button; Android alarm/notification plumbing follows. `PomodoroStatus` gains `OVERTIME`; `BREAK` becomes anchored on a new `breakStart` field set at closure. The active session snapshots its duration (`pomodoroSessionMinutes`) so the preset never clobbers the preferred duration.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, DataStore preferences, kotlinx.serialization (sync), Android AlarmManager.

**Spec:** `docs/superpowers/specs/2026-07-18-asymmetric-focus-design.md`

## Global Constraints

- Gate before EVERY commit: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest` — must pass with no errors or stderr.
- JDK 21 (`jvmToolchain(21)`); Android SDK required for the Android modules.
- Every new user-facing string goes to BOTH `shared/src/commonMain/composeResources/values/strings.xml` AND `values-fr/strings.xml` (Android res strings likewise in `androidApp/src/main/res/values*/`). Match the grammatical register already used in `values-fr` (check before writing).
- Compose UI tests: NEVER assert `stringResource` text; use test tags and seeded data. `assertExists` is a member function (no import).
- Commit messages in English; never any reference to Claude/Anthropic/AI; no test-plan or verification sections.
- Hard-coded defaults (no settings): quick preset = 5 min, overtime reminder at term + session duration (total 2×), break visibility decay = 60 min.
- Vocabulary: EN **Overtime** / FR **Élan**. No punitive copy anywhere; a failed gate is a silent no-op, never an error message with blame.

---

### Task 1: Core Pomodoro state machine — `OVERTIME`, anchored `BREAK`, formatters

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/Pomodoro.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/PomodoroTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `PomodoroStatus.OVERTIME`; `PomodoroProgress.overtimeElapsed: Duration`; `calculatePomodoroProgress(now, durationMinutes, end, breakStart: Instant? = null)`; `formatOvertimeLabel(progress: PomodoroProgress): String` ("+N min"); `BREAK_VISIBLE_MAX: Duration` (60.minutes). `breakElapsed` is now anchored on `breakStart`, not on the term.

- [ ] **Step 1: Write the failing tests** — append to `PomodoroTest.kt` (imports at top of file already include `Instant`, `minutes`; add what's missing):

```kotlin
@Test
fun pastTermIsOvertimeNotBreak() {
    val end = Instant.fromEpochMilliseconds(1_000_000L)
    val progress = calculatePomodoroProgress(end + 3.minutes, 25, end)
    assertEquals(PomodoroStatus.OVERTIME, progress.status)
    assertEquals(3.minutes, progress.overtimeElapsed)
    assertEquals(Duration.ZERO, progress.remaining)
}

@Test
fun breakComesFromBreakStartAnchor() {
    val breakStart = Instant.fromEpochMilliseconds(1_000_000L)
    val progress = calculatePomodoroProgress(breakStart + 7.minutes, 25, end = null, breakStart = breakStart)
    assertEquals(PomodoroStatus.BREAK, progress.status)
    assertEquals(7.minutes, progress.breakElapsed)
}

@Test
fun breakDecaysToIdleAfterSixtyMinutes() {
    val breakStart = Instant.fromEpochMilliseconds(1_000_000L)
    val progress = calculatePomodoroProgress(breakStart + 61.minutes, 25, end = null, breakStart = breakStart)
    assertEquals(PomodoroStatus.IDLE, progress.status)
}

@Test
fun activeSessionIgnoresStaleBreakStart() {
    val end = Instant.fromEpochMilliseconds(2_000_000L)
    val progress = calculatePomodoroProgress(end - 10.minutes, 25, end, breakStart = Instant.fromEpochMilliseconds(0L))
    assertEquals(PomodoroStatus.ACTIVE, progress.status)
}

@Test
fun overtimeLabelCeilsToWholeMinutesAndNeverShowsZero() {
    val end = Instant.fromEpochMilliseconds(1_000_000L)
    assertEquals("+1 min", formatOvertimeLabel(calculatePomodoroProgress(end + 10.seconds, 25, end)))
    assertEquals("+3 min", formatOvertimeLabel(calculatePomodoroProgress(end + 2.minutes + 10.seconds, 25, end)))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.PomodoroTest"`
Expected: FAIL — `OVERTIME` unresolved, `formatOvertimeLabel` unresolved.

- [ ] **Step 3: Implement in `Pomodoro.kt`**

Replace the enum, data class, and `calculatePomodoroProgress`; add the constant and formatter:

```kotlin
enum class PomodoroStatus { IDLE, ACTIVE, OVERTIME, BREAK }

/** How long a closed session's break stays visible before the panel returns to idle. */
val BREAK_VISIBLE_MAX: Duration = 60.minutes

data class PomodoroProgress(
    val durationMinutes: Int,
    val remaining: Duration,
    val remainingRatio: Float,
    val status: PomodoroStatus,
    val breakElapsed: Duration = Duration.ZERO,
    val overtimeElapsed: Duration = Duration.ZERO,
) {
    val remainingMinutes: Long get() = remaining.inWholeMinutes
    val remainingSeconds: Long get() = remaining.inWholeSeconds % 60
}

fun calculatePomodoroProgress(
    now: Instant,
    durationMinutes: Int,
    end: Instant?,
    breakStart: Instant? = null,
): PomodoroProgress {
    val safeDuration = durationMinutes.coerceIn(5, 180)
    val duration = safeDuration.minutes
    if (end != null) {
        val remaining = (end - now).coerceIn(Duration.ZERO, duration)
        return if (remaining > Duration.ZERO) {
            PomodoroProgress(safeDuration, remaining, (remaining / duration).toFloat(), PomodoroStatus.ACTIVE)
        } else {
            PomodoroProgress(
                durationMinutes = safeDuration,
                remaining = Duration.ZERO,
                remainingRatio = 0f,
                status = PomodoroStatus.OVERTIME,
                overtimeElapsed = (now - end).coerceAtLeast(Duration.ZERO),
            )
        }
    }
    if (breakStart != null) {
        val elapsed = (now - breakStart).coerceAtLeast(Duration.ZERO)
        if (elapsed <= BREAK_VISIBLE_MAX) {
            return PomodoroProgress(safeDuration, duration, 1f, PomodoroStatus.BREAK, breakElapsed = elapsed)
        }
    }
    return PomodoroProgress(safeDuration, duration, 1f, PomodoroStatus.IDLE)
}

/** Overtime headline: "+N min", ceiled so the first seconds already read "+1 min". */
fun formatOvertimeLabel(progress: PomodoroProgress): String {
    val minutes = ceil(progress.overtimeElapsed.toDouble(DurationUnit.MINUTES)).toLong().coerceAtLeast(1L)
    return "+$minutes min"
}
```

`formatBreakClock` keeps reading `breakElapsed` unchanged. Fix any now-broken compilation in the same module ONLY by adapting call sites mechanically (`calculatePomodoroProgress` gained an optional param — existing 3-arg calls still compile). The old `breakElapsed = now - end` line disappears with the rewrite.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.PomodoroTest"`
Expected: PASS. Note: other tests referencing `PomodoroStatus.BREAK` semantics may now fail — inspect each: tests that asserted "past term ⇒ BREAK" must be updated to expect `OVERTIME` (that is the spec'd behaviour change, not a regression). Update them in this task.

- [ ] **Step 5: Full gate, then commit**

```bash
./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest
git add -A && git commit -m "feat(core): overtime status and break anchored on closure"
```

(If `:shared`/`:androidApp` fail to compile because they exhaustively `when` over `PomodoroStatus`, add the minimal `OVERTIME ->` branches mirroring the existing `BREAK ->` branch for now — Tasks 9–12 give them their real behaviour.)

---

### Task 2: Exit gate + one-shot overtime reminder (pure logic)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/Pomodoro.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/PomodoroTest.kt`

**Interfaces:**
- Produces: `earlyExitRequiresDetour(now: Instant, end: Instant?, outcome: FocusClosureOutcome): Boolean`; `class OvertimeReminderScheduler { fun observe(now: Instant, sessionEnd: Instant?, sessionMinutes: Int): Boolean }` — fires exactly once per session when crossing `sessionEnd + sessionMinutes`.

- [ ] **Step 1: Failing tests**

```kotlin
@Test
fun earlyExitGateOnlyBeforeTermAndOnlyWhenNotCompleted() {
    val end = Instant.fromEpochMilliseconds(1_000_000L)
    assertTrue(earlyExitRequiresDetour(end - 1.minutes, end, FocusClosureOutcome.PROGRESSED))
    assertTrue(earlyExitRequiresDetour(end - 1.minutes, end, FocusClosureOutcome.TO_RESUME))
    assertFalse(earlyExitRequiresDetour(end - 1.minutes, end, FocusClosureOutcome.COMPLETED))
    assertFalse(earlyExitRequiresDetour(end, end, FocusClosureOutcome.PROGRESSED))
    assertFalse(earlyExitRequiresDetour(end + 1.minutes, end, FocusClosureOutcome.TO_RESUME))
    assertFalse(earlyExitRequiresDetour(end - 1.minutes, null, FocusClosureOutcome.PROGRESSED))
}

@Test
fun overtimeReminderFiresOnceAtDoubleDuration() {
    val end = Instant.fromEpochMilliseconds(1_000_000L)
    val scheduler = OvertimeReminderScheduler()
    scheduler.observe(end + 24.minutes, end, 25)
    assertTrue(scheduler.observe(end + 25.minutes + 5.seconds, end, 25))
    assertFalse(scheduler.observe(end + 26.minutes, end, 25))
}

@Test
fun overtimeReminderResetsWhenSessionChanges() {
    val end = Instant.fromEpochMilliseconds(1_000_000L)
    val scheduler = OvertimeReminderScheduler()
    scheduler.observe(end + 24.minutes, end, 25)
    assertTrue(scheduler.observe(end + 26.minutes, end, 25))
    val newEnd = end + 90.minutes
    scheduler.observe(newEnd + 24.minutes, newEnd, 25)
    assertTrue(scheduler.observe(newEnd + 26.minutes, newEnd, 25))
}

@Test
fun overtimeReminderSkipsWhenWakingLongPastThreshold() {
    val end = Instant.fromEpochMilliseconds(1_000_000L)
    val scheduler = OvertimeReminderScheduler()
    scheduler.observe(end + 1.minutes, end, 25)
    // Laptop asleep across the threshold: too stale to alert.
    assertFalse(scheduler.observe(end + 50.minutes, end, 25))
}
```

- [ ] **Step 2: Run — expect FAIL** (`./gradlew :core:jvmTest --tests "fr.dayview.app.PomodoroTest"`).

- [ ] **Step 3: Implement** (append to `Pomodoro.kt`; mirror `BreakReminderScheduler`'s lateness guard):

```kotlin
/** Leaving before the term with anything but COMPLETED must name the pull (a detour). */
fun earlyExitRequiresDetour(
    now: Instant,
    end: Instant?,
    outcome: FocusClosureOutcome,
): Boolean = end != null && now < end && outcome != FocusClosureOutcome.COMPLETED

/**
 * One discreet closure suggestion when a session's overtime reaches its planned duration
 * (total = 2x planned). Fires at most once per session; a wake long past the threshold
 * stays silent (same lateness rule as break reminders).
 */
class OvertimeReminderScheduler {
    private var sessionEnd: Instant? = null
    private var previous: Instant? = null
    private var fired: Boolean = false

    fun observe(
        now: Instant,
        sessionEnd: Instant?,
        sessionMinutes: Int,
    ): Boolean {
        if (sessionEnd != this.sessionEnd) {
            this.sessionEnd = sessionEnd
            previous = null
            fired = false
        }
        val previousObservation = previous
        previous = now
        if (sessionEnd == null || fired || previousObservation == null || now <= previousObservation) return false
        val threshold = sessionEnd + sessionMinutes.coerceIn(5, 180).minutes
        if (previousObservation < threshold && now >= threshold && now - threshold <= MAX_ALERT_LATENESS) {
            fired = true
            return true
        }
        return false
    }

    private companion object {
        val MAX_ALERT_LATENESS = 90.seconds
    }
}
```

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(core): early-exit detour gate and one-shot overtime reminder"`.

---

### Task 3: Session/break fields — UiState, snapshot, store keys

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (state fields + `pomodoroProgress` + snapshot mappers at lines ~837–947)
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`
- Test: `core/src/jvmTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`

**Interfaces:**
- Produces: `DayViewUiState.pomodoroSessionMinutes: Int?` (null when no session), `DayViewUiState.breakStart: Instant?`; same two fields on `DayPreferencesSnapshot`; store keys `"pomodoro_session_minutes"` / `"break_start"` with sentinel absence handling; `DayViewUiState.sessionMinutesEffective: Int` (= `pomodoroSessionMinutes ?: pomodoroMinutes`) used by every window derivation from now on.

- [ ] **Step 1: Failing round-trip test** in `DayPreferencesStoreTest.kt` (follow the file's existing pattern for building a snapshot and reading it back):

```kotlin
@Test
fun sessionMinutesAndBreakStartRoundTrip() = runStoreTest { store ->
    val snapshot = defaultSnapshot().copy(
        pomodoroSessionMinutes = 5,
        breakStart = Instant.fromEpochMilliseconds(123_456_000L),
    )
    store.persist(snapshot)
    val restored = store.snapshots.first()
    assertEquals(5, restored.pomodoroSessionMinutes)
    assertEquals(Instant.fromEpochMilliseconds(123_456_000L), restored.breakStart)
}

@Test
fun absentSessionKeysReadBackAsNull() = runStoreTest { store ->
    val restored = store.snapshots.first()
    assertNull(restored.pomodoroSessionMinutes)
    assertNull(restored.breakStart)
}
```

(Adapt the harness names — `runStoreTest`/`defaultSnapshot` — to whatever this test file actually uses; read it first.)

- [ ] **Step 2: Run — expect FAIL** (`./gradlew :core:jvmTest --tests "fr.dayview.app.DayPreferencesStoreTest"`).

- [ ] **Step 3: Implement.**

`DayPreferences.kt` — after `pomodoroEnd`:

```kotlin
val pomodoroSessionMinutes: Int? = null,
val breakStart: Instant? = null,
```

`DayPreferencesStore.kt` — key names in `DayPreferenceKeys`:

```kotlin
const val POMODORO_SESSION_MINUTES = "pomodoro_session_minutes"
const val BREAK_START = "break_start"
```

key vals next to `pomodoroEndKey`:

```kotlin
private val pomodoroSessionMinutesKey = intPreferencesKey(DayPreferenceKeys.POMODORO_SESSION_MINUTES)
private val breakStartKey = longPreferencesKey(DayPreferenceKeys.BREAK_START)
```

write path (next to line ~122), using the existing `NO_DEADLINE` sentinel convention for absent instants and `-1` for absent minutes:

```kotlin
prefs[pomodoroSessionMinutesKey] = snapshot.pomodoroSessionMinutes ?: -1
prefs[breakStartKey] = snapshot.breakStart?.toEpochMilliseconds() ?: DayPreferenceKeys.NO_DEADLINE
```

read path (next to line ~175, mirroring how `pomodoroEnd` decodes its sentinel):

```kotlin
pomodoroSessionMinutes = (this[pomodoroSessionMinutesKey] ?: -1).takeIf { it > 0 },
breakStart = (this[breakStartKey] ?: DayPreferenceKeys.NO_DEADLINE)
    .takeIf { it != DayPreferenceKeys.NO_DEADLINE }
    ?.let(Instant::fromEpochMilliseconds),
```

`DayViewController.kt` — `DayViewUiState` gains, after `focusIntention`:

```kotlin
val pomodoroSessionMinutes: Int? = null,
val breakStart: Instant? = null,
```

computed property + progress rewiring:

```kotlin
/** Duration of the running session when one exists, else the user's preferred duration. */
val sessionMinutesEffective: Int
    get() = pomodoroSessionMinutes ?: pomodoroMinutes

val pomodoroProgress: PomodoroProgress
    get() = calculatePomodoroProgress(now, sessionMinutesEffective, pomodoroEnd, breakStart)
```

Snapshot↔state mappers (the two blocks at ~lines 837–947): thread both fields through in both directions, exactly like `pomodoroEnd`.

- [ ] **Step 4: Run — expect PASS**, then the full `:core:jvmTest`.

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(core): session duration snapshot and break anchor persistence"`.

---

### Task 4: Cleanliness clamped at term; ledger takes session minutes

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt`

**Interfaces:**
- Consumes: `FocusClosureOutcome`, `OnGoalState`.
- Produces: `SessionCleanlinessTracker.observe` accumulates OFF_GOAL only up to `sessionEnd` (clamped); `closedFocusLedger(..., sessionMinutes: Int, ...)` — the `pomodoroMinutes` param is RENAMED to `sessionMinutes` (callers updated in Tasks 5–6 pass `sessionMinutesEffective`).

- [ ] **Step 1: Failing tests**

```kotlin
@Test
fun offGoalPastTermDoesNotAccumulate() {
    val end = Instant.fromEpochMilliseconds(1_000_000L)
    val tracker = SessionCleanlinessTracker()
    tracker.observe(end - 2.minutes, end, OnGoalState.OFF_GOAL)
    tracker.observe(end - 1.minutes, end, OnGoalState.OFF_GOAL) // 1 min inside the window
    tracker.observe(end + 5.minutes, end, OnGoalState.OFF_GOAL) // straddles the term: only up to `end` counts
    tracker.observe(end + 9.minutes, end, OnGoalState.OFF_GOAL) // fully past the term: nothing
    assertEquals(2.minutes, tracker.offGoalDuration)
}
```

- [ ] **Step 2: Run — expect FAIL** (`./gradlew :core:jvmTest --tests "fr.dayview.app.CleanFocusSessionsTest"`; currently accumulates 10 minutes).

- [ ] **Step 3: Implement.** In `SessionCleanlinessTracker.observe`, replace the accumulation line:

```kotlin
if (sessionEnd != null && state == OnGoalState.OFF_GOAL && previous != null && now > previous) {
    val cappedNow = minOf(now, sessionEnd)
    if (cappedNow > previous) accumulated += cappedNow - previous
}
```

In `closedFocusLedger`, rename the parameter `pomodoroMinutes` → `sessionMinutes` (window construction becomes `end - sessionMinutes.minutes`); update its KDoc. Update `closeFocusSnapshot` in the same file to pass `sessionMinutes = snapshot.pomodoroSessionMinutes ?: snapshot.pomodoroMinutes` (its fuller rework is Task 6 — here only keep it compiling). Update `DayViewController.closePomodoro`'s call likewise minimally (`sessionMinutes = state.sessionMinutesEffective`).

- [ ] **Step 4: Run — expect PASS** (whole `:core:jvmTest`).

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(core): clamp cleanliness at the term and key the ledger on session minutes"`.

---

### Task 5: Controller lifecycle — free start, exit toll, counted overtime

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (lines ~481–594)
- Test: `shared/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `earlyExitRequiresDetour`, `sessionMinutesEffective`, `closedFocusLedger(sessionMinutes = …)`.
- Produces (the UI wires to these in Tasks 9–10):
  - `startPomodoro(minutes: Int = state.pomodoroMinutes)` — no intention guard; no-ops when a session or an open detour is already running; snapshots `pomodoroSessionMinutes`; clears `breakStart`.
  - `closePomodoro(outcome: FocusClosureOutcome, intention: String = state.focusIntention, detourCategory: String = "", detourDescription: String = "")` — silently no-ops when the early-exit gate demands a detour and none is named; otherwise closes with `effectiveEnd = now` (uncapped), applies the edited intention to the record, sets `breakStart = now` UNLESS a detour is named (then chains `startOpenDetour`).
  - `stopPomodoro()` is DELETED.

- [ ] **Step 1: Failing tests** — append to `DayViewControllerTest.kt` (follow the file's existing controller construction helper):

```kotlin
@Test
fun startNeedsNoIntention() {
    val controller = makeController() // blank focusIntention
    controller.startPomodoro()
    assertNotNull(controller.state.pomodoroEnd)
    assertEquals(controller.state.pomodoroMinutes, controller.state.pomodoroSessionMinutes)
}

@Test
fun quickStartKeepsPreferredDuration() {
    val controller = makeController()
    val preferred = controller.state.pomodoroMinutes
    controller.startPomodoro(minutes = 5)
    assertEquals(5, controller.state.pomodoroSessionMinutes)
    assertEquals(preferred, controller.state.pomodoroMinutes)
}

@Test
fun startWhileSessionRunsIsIgnored() {
    val controller = makeController()
    controller.startPomodoro()
    val end = controller.state.pomodoroEnd
    controller.startPomodoro()
    assertEquals(end, controller.state.pomodoroEnd)
}

@Test
fun earlyProgressedWithoutDetourIsRefused() {
    val controller = makeController()
    controller.startPomodoro()
    controller.closePomodoro(FocusClosureOutcome.PROGRESSED)
    assertNotNull(controller.state.pomodoroEnd) // still running
}

@Test
fun earlyProgressedWithDetourClosesAndStartsOpenDetour() {
    val controller = makeController()
    controller.startPomodoro()
    controller.closePomodoro(FocusClosureOutcome.PROGRESSED, detourCategory = "Mail")
    assertNull(controller.state.pomodoroEnd)
    assertEquals("Mail", controller.state.openDetourCategory)
    assertNotNull(controller.state.openDetourStart)
    assertNull(controller.state.breakStart) // the detour replaces the break
}

@Test
fun earlyCompletedIsFree() {
    val controller = makeController()
    controller.startPomodoro()
    controller.closePomodoro(FocusClosureOutcome.COMPLETED)
    assertNull(controller.state.pomodoroEnd)
    assertNotNull(controller.state.breakStart)
}

@Test
fun closureRecordsOvertimeUncapped() {
    val controller = makeController()
    controller.startPomodoro()
    val end = controller.state.pomodoroEnd!!
    controller.advanceTo(end + 12.minutes) // adapt to the test harness's clock control
    controller.closePomodoro(FocusClosureOutcome.COMPLETED)
    val record = controller.state.focusSessionRecords.last()
    assertEquals(end + 12.minutes, record.end)
}

@Test
fun closureAppliesEditedIntentionToRecord() {
    val controller = makeController()
    controller.startPomodoro()
    controller.closePomodoro(FocusClosureOutcome.COMPLETED, intention = "revised chapter 3")
    assertEquals("revised chapter 3", controller.state.focusSessionRecords.last().intention)
}
```

- [ ] **Step 2: Run — expect FAIL** (`./gradlew :shared:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`).

- [ ] **Step 3: Implement** in `DayViewController.kt`:

```kotlin
fun startPomodoro(minutes: Int = state.pomodoroMinutes) {
    if (state.openDetourStart != null) return
    if (state.pomodoroEnd != null) return
    val sessionMinutes = minutes.coerceIn(5, 180)
    state = state.copy(
        pomodoroEnd = state.now + sessionMinutes.minutes,
        pomodoroSessionMinutes = sessionMinutes,
        breakStart = null,
        lastFocusClosure = null,
    )
    persistState()
}
```

Delete `stopPomodoro()`. In `changePomodoroDuration`, extend the guard to overtime and stop clearing the (now snapshotted) session window:

```kotlin
fun changePomodoroDuration(deltaMinutes: Int) {
    val status = state.pomodoroProgress.status
    if (status == PomodoroStatus.ACTIVE || status == PomodoroStatus.OVERTIME) return
    val updated = (state.pomodoroMinutes + deltaMinutes).coerceIn(5, 180)
    state = state.copy(pomodoroMinutes = updated)
    persistState()
}
```

`appendEngagedSession` and `recordClosingSession`: derive `start = end - state.sessionMinutesEffective.minutes` and `effectiveEnd = stopInstant` (drop the `minOf(stopInstant, end)` cap; update both KDocs — overtime now counts). `recordClosingSession` gains an `intention: String` parameter used in the `FocusSessionRecord`.

```kotlin
fun closePomodoro(
    outcome: FocusClosureOutcome,
    intention: String = state.focusIntention,
    detourCategory: String = "",
    detourDescription: String = "",
) {
    val end = state.pomodoroEnd ?: return
    val namedDetour = sanitizeDetourCategory(detourCategory).isNotEmpty()
    if (earlyExitRequiresDetour(state.now, end, outcome) && !namedDetour) return
    val trimmedIntention = intention.take(100)
    appendEngagedSession(state.now)
    recordClosingSession(state.now, outcome, trimmedIntention)
    val ledger = closedFocusLedger(
        cleanSessions = state.cleanSessions,
        dayKey = dayKeyOf(state.now),
        pomodoroEnd = state.pomodoroEnd,
        sessionMinutes = state.sessionMinutesEffective,
        sessionOffGoal = state.sessionOffGoal,
        detoursToday = state.detoursToday,
        outcome = outcome,
    )
    state = state.copy(
        pomodoroEnd = null,
        pomodoroSessionMinutes = null,
        breakStart = if (namedDetour) null else state.now,
        focusIntention = focusIntentionAfterClosure(trimmedIntention, outcome),
        lastFocusClosure = outcome,
        cleanSessions = ledger,
        sessionOffGoal = Duration.ZERO,
    )
    persistState()
    if (namedDetour) startOpenDetour(detourCategory, detourDescription)
}
```

Compilation ripple: `App.kt` references `controller.stopPomodoro()` — as a temporary bridge ONLY (real UI in Task 10), change that call site to `controller.closePomodoro(FocusClosureOutcome.TO_RESUME, detourCategory = "Focus interrompu")`? **No — do not invent data.** Instead have the `stopPomodoro` action in `App.kt` call `controller.closePomodoro(FocusClosureOutcome.COMPLETED)` when past term and no-op otherwise, with a `// Task 10 replaces this bridge` comment. Any desktop/Android caller of the removed method gets the same bridge.

- [ ] **Step 4: Run — expect PASS**, plus full `:shared:desktopTest` (existing `FocusFlowTest`/`FocusStartTest` failures caused by "start disabled without intention" assertions: update those assertions now — start is enabled and starts a session with a blank intention).

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(core): asymmetric focus lifecycle in the controller"`.

---

### Task 6: Mini-window snapshot path parity (`closeFocusSnapshot`)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt` (lines ~124–157)
- Test: `shared/src/commonTest/kotlin/fr/dayview/app/CloseFocusSnapshotTest.kt`

**Interfaces:**
- Produces: `closeFocusSnapshot(snapshot, now, sessionOffGoal, outcome, intention: String = snapshot.focusIntention, detourCategory: String = "", detourDescription: String = "")` with identical semantics to `closePomodoro`: gate, uncapped end, session-minutes window, `breakStart`/open-detour exclusivity, cleared `pomodoroSessionMinutes`.

- [ ] **Step 1: Failing tests** in `CloseFocusSnapshotTest.kt` (reuse its snapshot fixtures):

```kotlin
@Test
fun snapshotEarlyExitWithoutDetourIsRefused() {
    val s = runningSnapshot() // pomodoroEnd in the future
    val result = closeFocusSnapshot(s, now = s.pomodoroEnd!! - 10.minutes, sessionOffGoal = Duration.ZERO, outcome = FocusClosureOutcome.PROGRESSED)
    assertEquals(s, result)
}

@Test
fun snapshotEarlyExitWithDetourStartsOpenDetour() {
    val s = runningSnapshot()
    val now = s.pomodoroEnd!! - 10.minutes
    val result = closeFocusSnapshot(s, now, Duration.ZERO, FocusClosureOutcome.TO_RESUME, detourCategory = "Mail")
    assertNull(result.pomodoroEnd)
    assertEquals("Mail", result.openDetourCategory)
    assertEquals(now, result.openDetourStart)
    assertNull(result.breakStart)
}

@Test
fun snapshotOvertimeCloseRecordsUncappedAndAnchorsBreak() {
    val s = runningSnapshot()
    val now = s.pomodoroEnd!! + 8.minutes
    val result = closeFocusSnapshot(s, now, Duration.ZERO, FocusClosureOutcome.COMPLETED)
    assertEquals(now, result.focusSessionRecords.last().end)
    assertEquals(now, result.breakStart)
    assertNull(result.pomodoroSessionMinutes)
}
```

- [ ] **Step 2: Run — expect FAIL** (`./gradlew :shared:desktopTest --tests "fr.dayview.app.CloseFocusSnapshotTest"`).

- [ ] **Step 3: Implement** — rewrite `closeFocusSnapshot` mirroring Task 5's `closePomodoro` body on `DayPreferencesSnapshot` (uncapped `effectiveEnd = now`, `start = end - (snapshot.pomodoroSessionMinutes ?: snapshot.pomodoroMinutes).minutes`, `intention` param into the record and `focusIntentionAfterClosure`, gate via `earlyExitRequiresDetour`, `breakStart = now` unless a sanitized category is named in which case set `openDetourStart = now` / `openDetourCategory` / `openDetourDescription`).

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(core): mini-window closure path mirrors the asymmetric lifecycle"`.

---

### Task 7: Sync — `PomodoroDto` carries session minutes and break anchor

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/sync/SyncDocument.kt` (line 28)
- Modify: the snapshot↔document mappers (grep `PomodoroDto(` under `shared/src/commonMain/kotlin/fr/dayview/app/sync/` — expected in `SyncMerge.kt` and/or a codec file; update every construction/destructuring site)
- Test: the existing sync mapper test file (grep `PomodoroDto` under `shared/src/commonTest/.../sync/`)

**Interfaces:**
- Produces: `@Serializable data class PomodoroDto(val minutes: Int, val end: Long, val sessionMinutes: Int = -1, val breakStart: Long = -1L)` — `-1` sentinels mean absent, so documents from older clients decode unchanged.

- [ ] **Step 1: Failing tests** (in the sync test file located above):

```kotlin
@Test
fun pomodoroDtoDecodesLegacyJsonWithoutNewFields() {
    val dto = Json.decodeFromString<PomodoroDto>("""{"minutes":25,"end":123}""")
    assertEquals(-1, dto.sessionMinutes)
    assertEquals(-1L, dto.breakStart)
}

@Test
fun pomodoroRoundTripCarriesSessionAndBreak() {
    val snapshot = defaultSnapshot().copy(
        pomodoroMinutes = 25,
        pomodoroEnd = Instant.fromEpochMilliseconds(1_000L),
        pomodoroSessionMinutes = 5,
        breakStart = Instant.fromEpochMilliseconds(2_000L),
    )
    val restored = roundTripThroughSyncDocument(snapshot) // use the file's existing round-trip helper
    assertEquals(5, restored.pomodoroSessionMinutes)
    assertEquals(Instant.fromEpochMilliseconds(2_000L), restored.breakStart)
}
```

- [ ] **Step 2: Run — expect FAIL** (`./gradlew :shared:desktopTest --tests "*Sync*"`).

- [ ] **Step 3: Implement** — extend the DTO as above; at every mapper site encode `snapshot.pomodoroSessionMinutes ?: -1` / `snapshot.breakStart?.toEpochMilliseconds() ?: -1L` and decode `dto.sessionMinutes.takeIf { it > 0 }` / `dto.breakStart.takeIf { it > 0L }?.let(Instant::fromEpochMilliseconds)`. The whole DTO stays one LWW-versioned unit — no new `Versioned` field.

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(sync): session minutes and break anchor in the pomodoro document"`.

---

### Task 8: `TodaySnapshot` — overtime labels for native/menu-bar surfaces

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` (lines ~128–131, 175–179, 214–219)
- Test: create `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotFocusLineTest.kt`

**Interfaces:**
- Produces: `pomodoroStatus` may now be `"OVERTIME"` (document the new value in the existing comment; Swift falls through to a default — record as a PORT item in Task 13); `pomodoroClock` = `formatOvertimeLabel(...)` during OVERTIME; `focusLine` = `"Focus · <intention> · +N min"` (intention segment dropped when blank, in every status).

- [ ] **Step 1: Failing tests**

```kotlin
class TodaySnapshotFocusLineTest {
    private fun stateAt(now: Instant, end: Instant?, breakStart: Instant? = null, intention: String = "") =
        DayViewUiState(
            now = now, startMinutes = 8 * 60, endMinutes = 18 * 60, showSeconds = false,
            soundSettings = SoundSettings(), goalTitle = "", goalDeadlineText = "", goalDeadline = null,
            goalStartText = "", goalStart = null, pomodoroMinutes = 25, pomodoroEnd = end,
            focusIntention = intention, pomodoroSessionMinutes = if (end != null) 25 else null,
            breakStart = breakStart,
        )

    @Test
    fun overtimeStatusAndClock() {
        val end = Instant.parse("2026-07-18T10:00:00Z")
        val snap = stateAt(end + 3.minutes, end, intention = "write").toTodaySnapshot()
        assertEquals("OVERTIME", snap.pomodoroStatus)
        assertEquals("+3 min", snap.pomodoroClock)
        assertEquals("Focus · write · +3 min", snap.focusLine)
        assertEquals("+3 min", snap.menuBarTitle)
    }

    @Test
    fun blankIntentionDropsTheMiddleSegment() {
        val end = Instant.parse("2026-07-18T10:00:00Z")
        val active = stateAt(end - 10.minutes, end).toTodaySnapshot()
        assertEquals("Focus · 10:00", active.focusLine)
    }

    @Test
    fun breakLineAnchorsOnBreakStart() {
        val breakStart = Instant.parse("2026-07-18T10:00:00Z")
        val snap = stateAt(breakStart + 5.minutes, end = null, breakStart = breakStart).toTodaySnapshot()
        assertEquals("BREAK", snap.pomodoroStatus)
        assertEquals("Break · 05:00", snap.focusLine)
    }
}
```

(Exact `DayViewUiState` constructor args: match the real signature — positional/named as the data class requires.)

- [ ] **Step 2: Run — expect FAIL** (`./gradlew :core:jvmTest --tests "fr.dayview.app.TodaySnapshotFocusLineTest"`).

- [ ] **Step 3: Implement** in `toTodaySnapshot`:

```kotlin
val clock = when (pomodoro.status) {
    PomodoroStatus.ACTIVE -> formatPomodoroClock(pomodoro)
    PomodoroStatus.OVERTIME -> formatOvertimeLabel(pomodoro)
    PomodoroStatus.BREAK -> formatBreakClock(pomodoro)
    PomodoroStatus.IDLE -> ""
}
```

```kotlin
focusLine = when (pomodoro.status) {
    PomodoroStatus.ACTIVE, PomodoroStatus.OVERTIME ->
        if (focusIntention.isBlank()) "Focus · $clock" else "Focus · $focusIntention · $clock"
    PomodoroStatus.BREAK -> "Break · $clock"
    PomodoroStatus.IDLE -> ""
},
```

Extend the `pomodoroStatus` comment: `// "IDLE" | "ACTIVE" | "OVERTIME" | "BREAK" — …`.

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(core): overtime labels in the native snapshot"`.

---

### Task 9: Free entry UI — always-startable, optional-intention hint, 5-min preset

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`FocusCreationContent` ~2400–2450; `DayViewScreenActions` ~212)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/App.kt` (~596)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewMiniApp.kt` (start affordances)
- Modify: the test-tag registry (grep `object DayViewTestTags`)
- Modify: `shared/src/commonMain/composeResources/values/strings.xml` + `values-fr/strings.xml`
- Test: `shared/src/desktopTest/kotlin/fr/dayview/app/FocusStartTest.kt`

**Interfaces:**
- Consumes: `controller.startPomodoro(minutes)`.
- Produces: `DayViewScreenActions.quickStartPomodoro: () -> Unit`; test tags `DayViewTestTags.FocusQuickStart`; strings `focus_intention_optional_hint`, `focus_quick_start_button`.

- [ ] **Step 1: Failing UI tests** in `FocusStartTest.kt` (follow the file's `runComposeUiTest` harness; tags only, no string assertions):

```kotlin
@Test
fun startIsEnabledWithBlankIntention() = runComposeUiTest {
    setContent { /* the file's existing FocusPanel/today-screen harness with blank intention */ }
    onNodeWithTag(DayViewTestTags.FocusStart).assertIsEnabled()
}

@Test
fun quickStartLaunchesFiveMinuteSession() = runComposeUiTest {
    // harness with a real controller
    setContent { /* ... */ }
    onNodeWithTag(DayViewTestTags.FocusQuickStart).performClick()
    assertEquals(5, controller.state.pomodoroSessionMinutes)
}
```

- [ ] **Step 2: Run — expect FAIL** (`./gradlew :shared:desktopTest --tests "fr.dayview.app.FocusStartTest"`).

- [ ] **Step 3: Implement.**

Strings (EN; FR mirrors in `values-fr`, matching the file's existing register):

```xml
<string name="focus_intention_optional_hint">Optional — name it when you close.</string>
<string name="focus_quick_start_button">5 MIN</string>
```

FR:

```xml
<string name="focus_intention_optional_hint">Facultatif — nomme-la à la clôture.</string>
<string name="focus_quick_start_button">5 MIN</string>
```

`FocusCreationContent` (Task-anchor: `DayViewTodayScreen.kt:2435-2448`): the start button loses its gate and gains a sibling preset:

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FocusActionButton(
        if (desktopKeyboardShortcutsEnabled()) "$startLabel · ⌘↩" else startLabel,
        colors.amber,
        modifier = Modifier.weight(1f).testTag(DayViewTestTags.FocusStart),
        filled = true,
        onClick = onStart,
    )
    FocusActionButton(
        stringResource(Res.string.focus_quick_start_button),
        colors.amber,
        modifier = Modifier.testTag(DayViewTestTags.FocusQuickStart),
        onClick = onQuickStart,
    )
}
Spacer(Modifier.height(7.dp))
Text(stringResource(Res.string.focus_intention_optional_hint), color = colors.muted, fontSize = 10.sp)
```

(`enabled = intention.isNotBlank()` and the old `focus_intention_hint` conditional are removed; keep `focus_intention_hint` in the resource files unused-strings policy permitting — if ktlint/CI flags unused resources, delete the key from BOTH locales.) Thread `onQuickStart: () -> Unit` up through `FocusCreationContent` → `FocusPanel` → `DayViewScreen` → `DayViewScreenActions(quickStartPomodoro = …)`; in `App.kt`:

```kotlin
quickStartPomodoro = {
    controller.startPomodoro(minutes = 5)
    controller.state.pomodoroEnd?.let { onFocusAlarmChange(it, controller.state.focusIntention) }
},
```

Mini window: its start affordance keeps calling plain start (no preset there — YAGNI).

- [ ] **Step 4: Run — expect PASS** (`:shared:desktopTest`).

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(ui): zero-friction focus entry with optional intention and 5-min preset"`.

---

### Task 10: Closure sheet — intention at close, exit toll, overtime & break panel states

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/FocusControls.kt` (replace `FocusClosureSection`)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`FocusPanel` ~2135–2334; `DayViewScreenActions` stop/close entries)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/App.kt` (~602–611)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewMiniApp.kt` (~290–310)
- Modify: test tags + both `strings.xml`
- Test: `shared/src/desktopTest/kotlin/fr/dayview/app/FocusFlowTest.kt`

**Interfaces:**
- Consumes: `controller.closePomodoro(outcome, intention, detourCategory, detourDescription)`, `closeFocusSnapshot(...)` (mini), `formatOvertimeLabel`, `earlyExitRequiresDetour`.
- Produces:
  - `FocusClosureContent(intention: String, requiresDetourFor: (FocusClosureOutcome) -> Boolean, recentDetourCategories: List<String>, onClose: (FocusClosureOutcome, String, String, String) -> Unit, onCancel: (() -> Unit)? = null)` — replaces `FocusClosureSection` everywhere.
  - `DayViewScreenActions.closePomodoro: (FocusClosureOutcome, String, String, String) -> Unit`; `stopPomodoro` REMOVED from the actions class.
  - Test tags: `FocusClosureIntention`, `FocusExitDetourCategory`, `FocusExitDetourConfirm`, `FocusExitCancel`.

- [ ] **Step 1: Failing UI tests** in `FocusFlowTest.kt`:

```kotlin
@Test
fun stopDuringActiveOpensClosureNotAbort() = runComposeUiTest {
    // harness with a running session
    onNodeWithTag(DayViewTestTags.FocusStop).performClick()
    assertNotNull(controller.state.pomodoroEnd) // nothing aborted
    onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).assertExists()
}

@Test
fun earlyProgressedDemandsCategoryBeforeClosing() = runComposeUiTest {
    onNodeWithTag(DayViewTestTags.FocusStop).performClick()
    onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)).performClick()
    assertNotNull(controller.state.pomodoroEnd) // gate held
    onNodeWithTag(DayViewTestTags.FocusExitDetourCategory).performTextInput("Mail")
    onNodeWithTag(DayViewTestTags.FocusExitDetourConfirm).performClick()
    assertNull(controller.state.pomodoroEnd)
    assertEquals("Mail", controller.state.openDetourCategory)
}

@Test
fun overtimePanelShowsClosureWithoutStopRouting() = runComposeUiTest {
    // harness with now past the term
    onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).performClick()
    assertNull(controller.state.pomodoroEnd)
    assertNotNull(controller.state.breakStart)
}
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement.**

Strings (EN / FR):

```xml
<string name="focus_closure_intention_label">WHAT WAS IT?</string>
<string name="focus_exit_detour_label">NAME WHAT PULLS YOU AWAY</string>
<string name="focus_exit_detour_confirm">LEAVE VIA DETOUR</string>
<string name="focus_exit_cancel">STAY</string>
<string name="focus_state_overtime">OVERTIME — STILL COUNTED</string>
```

```xml
<string name="focus_closure_intention_label">C'ÉTAIT QUOI ?</string>
<string name="focus_exit_detour_label">NOMME CE QUI T'ATTIRE</string>
<string name="focus_exit_detour_confirm">PARTIR EN DÉTOUR</string>
<string name="focus_exit_cancel">RESTER</string>
<string name="focus_state_overtime">EN ÉLAN — TOUJOURS COMPTÉ</string>
```

(Adjust FR register to match the existing file if it uses vous/imperative-neutral forms.)

`FocusControls.kt` — replace `FocusClosureSection` with:

```kotlin
@Composable
internal fun FocusClosureContent(
    intention: String,
    requiresDetourFor: (FocusClosureOutcome) -> Boolean,
    recentDetourCategories: List<String>,
    onClose: (FocusClosureOutcome, String, String, String) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val colors = LocalDayViewColors.current
    var intentionText by remember(intention) { mutableStateOf(intention) }
    var pendingOutcome by remember { mutableStateOf<FocusClosureOutcome?>(null) }
    var category by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.focus_close_section), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.focus_closure_intention_label), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        // Reuse the app's existing small text-field composable (grep how FocusCreationContent renders the intention input) with tag FocusClosureIntention.
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (outcome in FocusClosureOutcome.entries) {
                FocusActionButton(
                    stringResource(outcome.labelRes()),
                    outcome.chipColor(colors),
                    modifier = Modifier.weight(1f).testTag(DayViewTestTags.focusOutcome(outcome)),
                    filled = pendingOutcome == outcome,
                    onClick = {
                        if (requiresDetourFor(outcome)) pendingOutcome = outcome
                        else onClose(outcome, intentionText, "", "")
                    },
                )
            }
        }
        pendingOutcome?.let { outcome ->
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.focus_exit_detour_label), color = colors.amber, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            // Recent-category one-tap chips: reuse the chip row from the detour capture sheet (grep DetoursUi.kt for the recent-motif chips) — tapping a chip sets `category`.
            // Category text field with tag FocusExitDetourCategory; optional description field mirroring DetoursUi's.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusActionButton(
                    stringResource(Res.string.focus_exit_detour_confirm),
                    colors.amber,
                    modifier = Modifier.weight(1f).testTag(DayViewTestTags.FocusExitDetourConfirm),
                    enabled = category.isNotBlank(),
                    filled = true,
                    onClick = { onClose(outcome, intentionText, category, description) },
                )
                onCancel?.let {
                    FocusActionButton(
                        stringResource(Res.string.focus_exit_cancel),
                        colors.muted,
                        modifier = Modifier.testTag(DayViewTestTags.FocusExitCancel),
                        onClick = { pendingOutcome = null; it() },
                    )
                }
            }
        }
    }
}
```

Extract `outcome.labelRes()` / `outcome.chipColor(colors)` as small private helpers mapping to the existing `focus_outcome_*` strings and mint/amber/muted colors. `FocusClosureOutcome.entries` order is COMPLETED, PROGRESSED, TO_RESUME — same as today's chips.

`FocusPanel` state machine:

- **ACTIVE**: keep intention/clock/progress; the Stop button flips a local `var showEarlyClosure by remember { mutableStateOf(false) }` (reset it via `remember(progress.status)` keying) instead of calling any action. When `showEarlyClosure`, render `FocusClosureContent` below the clock with `requiresDetourFor = { earlyExitRequiresDetour(state.now, state.pomodoroEnd, it) }`, `onCancel = { showEarlyClosure = false }`. The resume-ritual Stop routes to the same flag.
- **OVERTIME** (new branch): header label `focus_state_overtime` (mint); big label `formatOvertimeLabel(progress)` in place of the clock; `FocusClosureContent` always visible, `requiresDetourFor = { false }`, no cancel, no relaunch/stop round buttons.
- **BREAK** (rewrite of the old branch): break clock + the existing `focus_break_disconnect` / `focus_break_conscious` copy + `FocusRelaunchRoundButton(onStart)` ONLY — the closure section and stop button disappear (the session is already closed). The `breakElapsed >= 60.minutes` sub-branches die (BREAK now decays to IDLE at 60 min; `focus_state_series_inactive` becomes unused — remove the key from both locales).
- **IDLE**: unchanged from Task 9.

`DayViewScreenActions`: delete `stopPomodoro`; retype `closePomodoro: (FocusClosureOutcome, String, String, String) -> Unit`. `App.kt`:

```kotlin
closePomodoro = { outcome, intention, detourCategory, detourDescription ->
    controller.closePomodoro(outcome, intention, detourCategory, detourDescription)
    onFocusAlarmChange(null, controller.state.focusIntention)
    controller.state.breakStart?.let { onFocusBreakStarted(it.toEpochMilliseconds()) }
},
```

Add `onFocusBreakStarted: (Long) -> Unit = {}` as an `App` parameter next to `onFocusAlarmChange` (Android implements it in Task 12; desktop ignores it). Remove the Task-5 bridge. Mini window: replace its `FocusStopRoundButton`/`FocusClosureSection` pair with the same local-flag + `FocusClosureContent` wiring, calling the mini path `closeFocusSnapshot(snapshot, now, sessionOffGoal, outcome, intention, category, description)`.

- [ ] **Step 4: Run — expect PASS** (`:shared:desktopTest` full).

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(ui): closure carries the intention, early exits name a detour, overtime panel"`.

---

### Task 11: Desktop wiring — overtime reminder delivery, break-anchor swap

**Files:**
- Modify: grep `BreakReminderScheduler` under `shared/src/desktopMain/` and `shared/src/commonMain/` — the per-tick loop that calls `.observe(now, breakStart = …)` (expected in the desktop `Main.kt`/app loop next to `PresenceCoordinator`)
- Test: `shared/src/desktopTest/kotlin/fr/dayview/app/FocusFlowTest.kt` (pure scheduler wiring is already covered by Task 2; here only anchor correctness)

**Interfaces:**
- Consumes: `OvertimeReminderScheduler`, `state.breakStart`, `state.pomodoroSessionMinutes`, the existing break-reminder delivery channel (sound cue `BREAK_REMINDER` + notification path).

- [ ] **Step 1: Locate call sites**

Run: `grep -rn "BreakReminderScheduler\|breakReminder" shared/src --include="*.kt" | grep -v Test`
Expected: the tick loop passing `pomodoroEnd` (or the progress' break start) as the anchor.

- [ ] **Step 2: Swap the anchor and add the overtime reminder**

At the located tick site: `breakReminderScheduler.observe(now, state.breakStart)` (was the term). Instantiate one `OvertimeReminderScheduler` alongside and each tick:

```kotlin
if (overtimeReminderScheduler.observe(now, state.pomodoroEnd, state.sessionMinutesEffective)) {
    // Deliver through the same channel the break reminder uses at this site
    // (sound cue + notification), with the overtime strings from Task 12/13.
}
```

If the desktop delivery needs user-visible copy now, add `overtime_reminder_title` / `overtime_reminder_body` to both shared locales here (EN: "Still going — closing stays a choice." / "The session passed twice its length."; FR: "Toujours en élan — clôturer reste un choix." / "La session a doublé sa durée.").

- [ ] **Step 3: Overtime on the JVM menu bar and mini window**

These do NOT go through `TodaySnapshot`. Run: `grep -rn "PomodoroStatus\." shared/src/desktopMain shared/src/commonMain/kotlin/fr/dayview/app/DayViewMiniApp.kt --include="*.kt" | grep -v Test`. Every `when` over the status (expected: `MacFocusStatusItem` title, mini-window clock) gets an `OVERTIME ->` branch rendering `formatOvertimeLabel(progress)` where `ACTIVE` renders `formatPomodoroClock(progress)` — Kotlin's exhaustive `when` will have flagged these sites since Task 1; replace any stop-gap branch added there with this real rendering. `MacFocusStatusItemTest` gets one assertion: status-item title equals `"+3 min"` for a progress 3 minutes past term (build the progress via `calculatePomodoroProgress` like the file's existing cases).

- [ ] **Step 4: Run the gate's desktop slice**

Run: `./gradlew :shared:desktopTest`
Expected: PASS.

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(desktop): overtime reminder and break reminders anchored on closure"`.

---

### Task 12: Android — overtime notification, second alarm, closure-anchored break

**Files:**
- Modify: `androidApp/src/main/kotlin/fr/dayview/app/FocusAlarm.kt`
- Modify: `androidApp/src/main/kotlin/fr/dayview/app/FocusNotification.kt`
- Modify: `androidApp/src/main/res/values/strings.xml` + `values-fr/strings.xml`
- Modify: grep callers of `FocusAlarmScheduler(...)` and `restoreBreakReminders` in `androidApp/` (MainActivity/App bridge) — thread `sessionMinutes` and the new `close(...)`/`onFocusBreakStarted` path
- Modify: `androidApp/src/main/kotlin/fr/dayview/app/DayViewWidget.kt` + `DayViewFocusTileService.kt` if they branch on `PomodoroStatus` (grep) — add `OVERTIME` branches showing `formatOvertimeLabel`
- Test: `androidApp/src/test/kotlin/fr/dayview/app/FocusAlarmTest.kt`

**Interfaces:**
- Produces: `FocusAlarmScheduler.schedule(endMillis, intention, sessionMinutes: Int)`; `FocusAlarmScheduler.close(breakStartMillis, intention)`; `FocusNotificationManager.showOvertime(endMillis, intention)`; receiver kind `KIND_OVERTIME_REMINDER`; request code `OVERTIME_ALARM_REQUEST_CODE = 4104`.

- [ ] **Step 1: Failing tests** in `FocusAlarmTest.kt` (Robolectric; follow the file's alarm-assertion pattern):

```kotlin
@Test
fun scheduleArmsEndAndOvertimeAlarms() {
    scheduler.schedule(endMillis = now + 25 * 60_000L, intention = "", sessionMinutes = 25)
    // assert two alarms: one at end, one at end + 25 min (inspect ShadowAlarmManager)
}

@Test
fun closeCancelsOvertimeAndSchedulesBreakReminderFromClosure() {
    scheduler.schedule(endMillis = now + 25 * 60_000L, intention = "", sessionMinutes = 25)
    scheduler.close(breakStartMillis = now + 30 * 60_000L, intention = "")
    // assert overtime alarm gone; break reminder armed at closure + 10 min
}
```

- [ ] **Step 2: Run — expect FAIL** (`./gradlew :androidApp:testDebugUnitTest --tests "fr.dayview.app.FocusAlarmTest"`).

- [ ] **Step 3: Implement.**

`FocusAlarmScheduler`:
- `schedule(endMillis, intention, sessionMinutes)`: after arming the end alarm, arm a second exact-or-inexact alarm at `endMillis + sessionMinutes * 60_000L` via a new `overtimeReminderPendingIntent(context, endMillis)` (`OVERTIME_ALARM_REQUEST_CODE = 4104`, extra `EXTRA_KIND = KIND_OVERTIME_REMINDER`). Store `sessionMinutes` in the focus-end intent as `EXTRA_SESSION_MINUTES`.
- `cancel()`: also cancel the overtime pending intent.
- New `close(breakStartMillis, intention)`: cancel end + overtime alarms, `FocusNotificationManager.showBreak(breakStartMillis, intention)`, `scheduleBreakReminder(breakStartMillis, BREAK_INTERVAL_MINUTES)`, tile refresh. `restoreBreakReminders` keeps its body but is now called with `snapshot.breakStart` millis by its caller (grep and update; skip the call when `breakStart == null`).

`FocusAlarmReceiver.onReceive`:
- `KIND_FOCUS_END`: replace `restoreBreakReminders(...)` with `FocusNotificationManager(context).showOvertime(breakStartMillis /* = endMillis extra */, intention)` — the ongoing notification flips to count-up; the existing heads-up chime notification below stays as-is (the invitation).
- `KIND_OVERTIME_REMINDER`: post the heads-up using new strings `R.string.overtime_reminder_title` / `R.string.overtime_reminder_body`; do not reschedule anything.
- `KIND_BREAK_REMINDER`: unchanged.

`FocusNotificationManager.showOvertime(endMillis, intention)`: clone `showBreak` with `setWhen(endMillis)`, `setChronometerCountDown(false)`, title `R.string.focus_notification_overtime_title`, and a single action labeled `R.string.focus_notification_close` whose PendingIntent is `PendingIntent.getActivity` to `MainActivity` (closing requires the in-app ritual). In `showFocus`, replace the Stop broadcast action with the same open-`MainActivity` activity action (the silent `ACTION_STOP_FOCUS` bypass dies); delete the `ACTION_STOP_FOCUS` branch from `FocusNotificationActionReceiver` and its constant. In `ACTION_RESUME_FOCUS`, also persist `pomodoroSessionMinutes = durationMinutes, breakStart = null`.

Android strings (EN + FR):

```xml
<string name="focus_notification_overtime_title">Focus — running over, still counted</string>
<string name="focus_notification_close">Close</string>
<string name="overtime_reminder_title">Still going — closing stays a choice</string>
<string name="overtime_reminder_body">The session passed twice its length.</string>
```

```xml
<string name="focus_notification_overtime_title">Focus — en élan, toujours compté</string>
<string name="focus_notification_close">Clôturer</string>
<string name="overtime_reminder_title">Toujours en élan — clôturer reste un choix</string>
<string name="overtime_reminder_body">La session a doublé sa durée prévue.</string>
```

Callers: the `onFocusAlarmChange` bridge (grep in `MainActivity`/Android app wiring) now passes `controller.state.sessionMinutesEffective`; wire `onFocusBreakStarted = { FocusAlarmScheduler(context).close(it, /* intention */ "") }`. Widget/tile: grep `PomodoroStatus` — exhaustive `when`s get an `OVERTIME` branch rendering `formatOvertimeLabel(progress)` where ACTIVE renders the clock.

- [ ] **Step 4: Run — expect PASS** (`:androidApp:testDebugUnitTest`). Also run `./gradlew :androidApp:assembleRelease` once — R8 catches dependency issues the unit gate misses.

- [ ] **Step 5: Full gate, commit** — `git commit -m "feat(android): overtime notification, one-shot reminder alarm, closure-anchored break"`.

---

### Task 13: Vocabulary, docs, parity checklist

**Files:**
- Modify: `README.md` (vocabulary table, lines ~19–33; Focus feature section)
- Modify: `docs/superpowers/macos-native-parity-checklist.md` (Parking Lot / PORT items)
- Test: none (docs)

- [ ] **Step 1: README vocabulary table** — add the row:

```markdown
| Time worked past the timer's term, counted until closure | **Overtime** | **Élan** |
```

Update the Focus section prose: intention optional (invited at closure), 5-min preset, early exits name a detour, term = invitation, overtime counted, break starts at closure.

- [ ] **Step 2: Parity checklist** — append PORT items: `"OVERTIME" pomodoroStatus value in the Swift status switch`, `closure sheet with intention field + exit-toll detour capture in native`, `5-min preset button`, `overtime "+N min" in MenuBarContent/MiniView`, `break anchored on breakStart`.

- [ ] **Step 3: Sweep check** — `grep -rn "focus_state_series_inactive\|focus_intention_hint" shared/` returns nothing (both keys removed from BOTH locales with their usages); `grep -rn "stopPomodoro" --include="*.kt" .` returns nothing.

- [ ] **Step 4: Full gate, commit** — `git commit -m "docs: overtime vocabulary and native parity items for the asymmetric focus"`.

---

## Self-Review Notes

- Spec §"Free entry" → Tasks 5, 9. §"Quick preset" → 5, 9. §"Finishing free / fleeing named" → 2, 5, 6, 10. §"Overtime counts" → 1, 3, 5, 8, 10, 11, 12. §"Soft reminder" → 2, 11, 12. §"State model" → 1, 3. §"Reminders and sounds" → 11, 12. §"UI" → 9, 10 (drift-nudge fallback already handled: `intention.ifBlank { focus_single_thing }` exists at every nudge render site; TodaySnapshot fallback in Task 8). §"Out of scope" respected: no Android detection, no blocking, no streak changes.
- Type consistency: `closedFocusLedger(sessionMinutes)` renamed in Task 4, consumed with that name in 5 and 6. `FocusClosureContent` 4-arg `onClose` matches `DayViewScreenActions.closePomodoro` and `closeFocusSnapshot`'s params. `sessionMinutesEffective` defined in Task 3, consumed in 5, 11, 12.
- Known intentional behaviour changes riding along (all spec-consistent): relaunch can no longer silently replace an unclosed session (`startPomodoro` guards `pomodoroEnd != null`); duration change no longer clears a session window; the Android notification's silent Stop bypass is removed.
