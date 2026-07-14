# Focus Session Time (Engaged Time) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Report a second, lenient "engaged time" figure alongside the existing strict "focus time", historised day + week, without touching sync.

**Architecture:** Generalise the existing `PresenceAccumulator` into a parameterised inclusion accumulator and instantiate it twice — strict (unchanged defaults) and lenient. The lenient accumulator feeds a new desktop-local `focusSessionIntervals` interval log that mirrors `focusPresenceIntervals` end to end (persistence, history codec, UI readout), and re-derives an engaged duration through the existing `focusedTime` projection.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, AndroidX DataStore (desktop), kotlin.time.

## Global Constraints

- Test/lint gate must be green: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- ktlint is enforced — run `./gradlew ktlintFormat` before committing if needed.
- Commit messages: English, describe the change only. **Never** reference Claude/Anthropic/AI, never add a Co-Authored-By trailer, never add a test-plan or verification section, never reference internal planning docs.
- Compose UI tests: never assert `stringResource` text (unresolved under `runComposeUiTest` on CI) — assert on test tags / seeded data. `assertExists` is a member (no import).
- Do not touch `SyncDocument` / `SyncMapper`: the new interval log is desktop-local and unsynced, exactly like `focusPresenceIntervals`.
- Lenient accumulator parameters (fixed by the spec): `presentStates = {ON_GOAL, NEUTRAL}`, `bridge = 120s`, `minInterval = 60s`, `interruptionGap = 15s`. Strict keeps today's defaults (`{ON_GOAL}`, `30s`, `120s`, interruption disabled).

---

### Task 1: Generalise `PresenceAccumulator`

Refactor the accumulator to a parameterised inclusion model and add an unobserved-interruption rule, keeping the zero-arg constructor byte-for-byte behaviour-compatible so the existing test suite stays green.

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/PresenceAccumulator.kt` (class `PresenceAccumulator`, lines 33-108)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/PresenceAccumulatorTest.kt` (add lenient cases; existing cases unchanged)

**Interfaces:**
- Consumes: `OnGoalState`, `FocusPresenceInterval` (existing).
- Produces:
  - `class PresenceAccumulator(presentStates: Set<OnGoalState> = setOf(OnGoalState.ON_GOAL), bridge: Duration = 30.seconds, minInterval: Duration = 120.seconds, interruptionGap: Duration? = null)`
  - `fun observe(now: Instant, state: OnGoalState, dayKey: Long): List<FocusPresenceInterval>`
  - `fun endSession(): List<FocusPresenceInterval>`
  - `fun restore(intervals: List<FocusPresenceInterval>, dayKey: Long)`

- [ ] **Step 1: Add lenient test cases (failing)**

Append to `PresenceAccumulatorTest.kt`, inside the class:

```kotlin
    private fun lenient() = PresenceAccumulator(
        presentStates = setOf(OnGoalState.ON_GOAL, OnGoalState.NEUTRAL),
        bridge = 120.seconds,
        minInterval = 60.seconds,
        interruptionGap = 15.seconds,
    )

    // Feed ticks from [from]..[to] inclusive at [step] cadence (models continuous 1s observation).
    private fun run(a: PresenceAccumulator, from: Long, to: Long, step: Long, state: OnGoalState): List<FocusPresenceInterval> {
        var result = emptyList<FocusPresenceInterval>()
        var t = from
        while (t <= to) {
            result = a.observe(ms(t), state, day)
            t += step
        }
        return result
    }

    @Test
    fun lenientCountsNeutralTime() {
        val a = lenient()
        val result = run(a, 0L, 90_000L, 10_000L, OnGoalState.NEUTRAL)
        assertEquals(listOf(FocusPresenceInterval(ms(0L), ms(90_000L))), result)
    }

    @Test
    fun lenientCountsBriefOffGoalUnderBridge() {
        val a = lenient()
        run(a, 0L, 60_000L, 10_000L, OnGoalState.ON_GOAL) // lastPresent = 60000
        run(a, 70_000L, 160_000L, 10_000L, OnGoalState.OFF_GOAL) // 100s off-goal, never reaches 120s bridge
        val result = run(a, 170_000L, 170_000L, 10_000L, OnGoalState.ON_GOAL) // back on-goal, blip absorbed
        assertEquals(listOf(FocusPresenceInterval(ms(0L), ms(170_000L))), result)
    }

    @Test
    fun lenientDropsSustainedOffGoalDrift() {
        val a = lenient()
        run(a, 0L, 60_000L, 10_000L, OnGoalState.ON_GOAL) // lastPresent = 60000
        val result = run(a, 70_000L, 200_000L, 10_000L, OnGoalState.OFF_GOAL) // reaches 120s bridge at t=180000
        assertEquals(listOf(FocusPresenceInterval(ms(0L), ms(60_000L))), result)
    }

    @Test
    fun lenientExcludesUnobservedInterruption() {
        val a = lenient()
        run(a, 0L, 60_000L, 10_000L, OnGoalState.ON_GOAL) // interval 1: [0, 60000]
        // 40s tick gap (>= 15s interruptionGap) closes interval 1; a fresh interval starts on resume
        val result = run(a, 100_000L, 170_000L, 10_000L, OnGoalState.ON_GOAL)
        assertEquals(
            listOf(
                FocusPresenceInterval(ms(0L), ms(60_000L)),
                FocusPresenceInterval(ms(100_000L), ms(170_000L)),
            ),
            result,
        )
    }

    @Test
    fun lenientDiscardsRunBelowMinInterval() {
        val a = lenient()
        run(a, 0L, 40_000L, 10_000L, OnGoalState.ON_GOAL) // 40s run, below 60s minInterval
        // 160s tick gap (>= 15s) closes the run; 40s < 60s so it is discarded
        val result = a.observe(ms(200_000L), OnGoalState.ON_GOAL, day)
        assertEquals(emptyList(), result)
    }
```

Add the imports at the top of the file:

```kotlin
import kotlin.time.Duration.Companion.seconds
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.PresenceAccumulatorTest"`
Expected: FAIL — compilation error (constructor has no `presentStates`/`interruptionGap` parameters).

- [ ] **Step 3: Rewrite the accumulator with parameters + interruption rule**

Replace the class body (lines 33-108) of `PresenceAccumulator.kt` with:

```kotlin
class PresenceAccumulator(
    private val presentStates: Set<OnGoalState> = setOf(OnGoalState.ON_GOAL),
    private val bridge: Duration = 30.seconds,
    private val minInterval: Duration = 120.seconds,
    private val interruptionGap: Duration? = null,
) {
    private val closed = mutableListOf<FocusPresenceInterval>()
    private var openStart: Instant? = null
    private var lastPresent: Instant? = null
    private var lastObserved: Instant? = null
    private var currentDayKey: Long = Long.MIN_VALUE

    /** Seed closed intervals for [dayKey] at startup (persisted state). */
    fun restore(
        intervals: List<FocusPresenceInterval>,
        dayKey: Long,
    ) {
        currentDayKey = dayKey
        closed.clear()
        closed.addAll(intervals)
        openStart = null
        lastPresent = null
        lastObserved = null
    }

    /**
     * Finalize the open run at a focus-session boundary: commit it if it meets the
     * minimum, then clear it so the next session starts a fresh interval rather than
     * bridging across the inactive gap. Returns the current closed intervals.
     */
    fun endSession(): List<FocusPresenceInterval> {
        closeOpenRun()
        return closed.toList()
    }

    fun observe(
        now: Instant,
        state: OnGoalState,
        dayKey: Long,
    ): List<FocusPresenceInterval> {
        if (dayKey != currentDayKey) {
            currentDayKey = dayKey
            closed.clear()
            openStart = null
            lastPresent = null
            lastObserved = null
        }
        // An unobserved gap between ticks (machine asleep / app backgrounded) never counts:
        // close the open run at the last observed present tick before continuing.
        val previousObserved = lastObserved
        if (interruptionGap != null && previousObserved != null && now - previousObserved >= interruptionGap) {
            closeOpenRun()
        }
        if (state in presentStates) {
            if (openStart == null) openStart = now
            lastPresent = now
        } else {
            val start = openStart
            val last = lastPresent
            if (start != null && last != null && now - last >= bridge) {
                closeOpenRun()
            }
        }
        lastObserved = now
        return snapshotIntervals()
    }

    /** Commit `[openStart, lastPresent]` iff it meets [minInterval], then clear the open run. */
    private fun closeOpenRun() {
        val start = openStart
        val last = lastPresent
        if (start != null && last != null && last - start >= minInterval) {
            closed.add(FocusPresenceInterval(start, last))
        }
        openStart = null
    }

    private fun snapshotIntervals(): List<FocusPresenceInterval> {
        val start = openStart
        val last = lastPresent
        if (start != null && last != null && last - start >= minInterval) {
            return closed + FocusPresenceInterval(start, last)
        }
        return closed.toList()
    }
}
```

Leave lines 1-32 (package, imports, `FocusPresenceInterval`, `encodeFocusPresence`, `decodeFocusPresence`, the class KDoc) unchanged. The file already imports `Duration`, `seconds`, and `Instant`.

- [ ] **Step 4: Run the full accumulator suite (new + existing regression) to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.PresenceAccumulatorTest"`
Expected: PASS — all 5 new lenient tests plus the 7 pre-existing strict tests (the zero-arg constructor is unchanged behaviour).

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/PresenceAccumulator.kt core/src/commonTest/kotlin/fr/dayview/app/PresenceAccumulatorTest.kt
git commit -m "Generalise presence accumulator with configurable inclusion and interruption rule"
```

---

### Task 2: `focusSessionIntervals` state + `sessionFocusedToday` + controller wiring

Add the lenient interval log to `DayViewUiState`, derive the engaged duration through the existing `focusedTime`, and give the controller a seed parameter and setter mirroring `focusPresenceIntervals`.

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (`DayViewUiState` data class ~line 49 and getters ~line 115; controller constructor ~line 168 and initial `_stateFlow` copy ~line 176; `setFocusPresenceIntervals` ~line 432)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `PresenceAccumulator` output shape `List<FocusPresenceInterval>`, existing `focusedTime(windowStart, windowEnd, intervals)`.
- Produces:
  - `DayViewUiState.focusSessionIntervals: List<FocusPresenceInterval>`
  - `DayViewUiState.sessionFocusedToday: Duration`
  - `DayViewController(..., initialFocusSessionIntervals: List<FocusPresenceInterval> = emptyList(), ...)`
  - `DayViewController.setFocusSessionIntervals(intervals: List<FocusPresenceInterval>)`

- [ ] **Step 1: Write the failing test**

Add to `DayViewControllerTest.kt`:

```kotlin
    @Test
    fun sessionFocusedTodayDerivesFromSessionIntervals() {
        val controller = newController() // reuse the test's existing controller factory
        val windowStart = controller.state.dayWindow.first
        controller.setFocusSessionIntervals(
            listOf(FocusPresenceInterval(windowStart, windowStart + 30.minutes)),
        )
        assertEquals(30.minutes, controller.state.sessionFocusedToday)
    }
```

If `DayViewControllerTest` has no shared `newController()` helper, construct the controller inline the same way the neighbouring tests in that file do, then set the intervals. Add imports as needed: `import fr.dayview.app.FocusPresenceInterval` is same-package (no import), `import kotlin.time.Duration.Companion.minutes`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `setFocusSessionIntervals` / `sessionFocusedToday` unresolved.

- [ ] **Step 3: Add the field, derived getter, seed, and setter**

In `DayViewUiState`, add after line 49 (`val focusPresenceIntervals: ... = emptyList(),`):

```kotlin
    val focusSessionIntervals: List<FocusPresenceInterval> = emptyList(),
```

Add after the `focusedToday` getter (after line 119):

```kotlin

    val sessionFocusedToday: Duration
        get() {
            val (start, end) = dayWindow
            return focusedTime(start, end, focusSessionIntervals)
        }
```

In the `DayViewController` constructor, add a parameter after `initialFocusPresenceIntervals` (line 168):

```kotlin
    initialFocusSessionIntervals: List<FocusPresenceInterval> = emptyList(),
```

Extend the initial `_stateFlow` seed copy (line 176) to also seed the session intervals:

```kotlin
    private val _stateFlow = MutableStateFlow(
        initialSnapshot.toUiState(initialNow).copy(
            focusPresenceIntervals = initialFocusPresenceIntervals,
            focusSessionIntervals = initialFocusSessionIntervals,
        ),
    )
```

Add the setter next to `setFocusPresenceIntervals` (after line 434):

```kotlin

    fun setFocusSessionIntervals(intervals: List<FocusPresenceInterval>) {
        state = state.copy(focusSessionIntervals = intervals)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Add session-focus interval state and engaged duration to day view state"
```

---

### Task 3: History record + codec

Carry `focusSessionIntervals` into the day-history snapshot and its text codec, clipped to the day window like presence, decoding legacy records (no `session=` line) to an empty list.

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayHistoryRecord.kt` (data class ~line 23, `toFrozenUiState` ~line 71, `toHistoryRecord` ~line 96)
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayHistoryCodec.kt` (`encode` ~line 32, `decode` ~line 69)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/` — the existing `DayHistoryCodec` round-trip test (find it with the search command in Step 1)

**Interfaces:**
- Consumes: `encodeFocusPresence` / `decodeFocusPresence`, `FocusPresenceInterval`, `DayViewUiState.focusSessionIntervals` (Task 2).
- Produces: `DayHistoryRecord.focusSessionIntervals: List<FocusPresenceInterval>`; codec line `session=<base64>`.

- [ ] **Step 1: Locate the codec test and write the failing round-trip case**

Run: `grep -rln "DayHistoryCodec\|dayhistory" core/src/commonTest`
Open the codec test file it reports. Add a test that builds a record with a non-empty `focusSessionIntervals`, encodes, decodes, and asserts the field survives; plus a legacy-decode test:

```kotlin
    @Test
    fun roundTripPreservesFocusSessionIntervals() {
        val record = sampleRecord().copy( // reuse the file's existing record builder/helper
            focusSessionIntervals = listOf(
                FocusPresenceInterval(
                    Instant.fromEpochMilliseconds(1_000_000L),
                    Instant.fromEpochMilliseconds(2_000_000L),
                ),
            ),
        )
        val decoded = DayHistoryCodec.decode(DayHistoryCodec.encode(record))
        assertEquals(record.focusSessionIntervals, decoded?.focusSessionIntervals)
    }

    @Test
    fun decodesLegacyRecordWithoutSessionLineAsEmpty() {
        val record = sampleRecord()
        val legacy = DayHistoryCodec.encode(record)
            .lines()
            .filterNot { it.startsWith("session=") }
            .joinToString("\n")
        val decoded = DayHistoryCodec.decode(legacy)
        assertEquals(emptyList(), decoded?.focusSessionIntervals)
    }
```

If the test file has no `sampleRecord()` helper, construct a `DayHistoryRecord(...)` inline exactly as the neighbouring tests do (all constructor args), setting `focusSessionIntervals` explicitly. Ensure `import kotlin.time.Instant` is present.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.*Codec*"`
Expected: FAIL — `focusSessionIntervals` is not a constructor parameter of `DayHistoryRecord`.

- [ ] **Step 3: Add the field and codec line**

In `DayHistoryRecord.kt`, add to the data class after `focusPresenceIntervals` (line 23):

```kotlin
    val focusSessionIntervals: List<FocusPresenceInterval>,
```

In `toFrozenUiState` (after line 71 `focusPresenceIntervals = focusPresenceIntervals,`):

```kotlin
    focusSessionIntervals = focusSessionIntervals,
```

In `toHistoryRecord` (after line 96, mirroring the presence clip):

```kotlin
        focusSessionIntervals = focusSessionIntervals.filter { it.end > windowStart && it.start < windowEnd },
```

In `DayHistoryCodec.encode`, add after the `presence=` line (line 32):

```kotlin
        appendLine("session=${enc(encodeFocusPresence(record.focusSessionIntervals))}")
```

In `DayHistoryCodec.decode`, add after the `focusPresenceIntervals = ...` line (line 69). Use a defaulting read (NOT `req`) so legacy records decode:

```kotlin
                focusSessionIntervals = map["session"]?.let { decodeFocusPresence(dec(it)) } ?: emptyList(),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.*Codec*"`
Expected: PASS. Also run `./gradlew :core:jvmTest` to confirm no other core test broke on the new required constructor field (fix any inline `DayHistoryRecord(...)` construction sites in tests by adding `focusSessionIntervals = emptyList()`).

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayHistoryRecord.kt core/src/commonMain/kotlin/fr/dayview/app/DayHistoryCodec.kt core/src/commonTest/kotlin/fr/dayview/app/
git commit -m "Persist session-focus intervals in day history record and codec"
```

---

### Task 4: Desktop persistence (`saveFocusSession` / `loadFocusSession`)

Add a DataStore-backed twin of the focus-presence persistence, desktop-only.

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopPreferences.kt` (keys ~line 21-24, methods ~line 41-52)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DesktopDataStoreTest.kt`

**Interfaces:**
- Consumes: `encodeFocusPresence` / `decodeFocusPresence`, `FocusPresenceInterval`.
- Produces:
  - `DesktopPreferences.loadFocusSession(): Pair<Long, List<FocusPresenceInterval>>`
  - `DesktopPreferences.saveFocusSession(dayKey: Long, intervals: List<FocusPresenceInterval>)`

- [ ] **Step 1: Write the failing test**

Add to `DesktopDataStoreTest.kt`, following the pattern the file already uses to build a `DesktopPreferences` over a temp DataStore (reuse its existing setup/helper):

```kotlin
    @Test
    fun savesAndLoadsFocusSessionIntervals() = runTest {
        val prefs = newDesktopPreferences() // reuse the file's existing temp-store factory
        val intervals = listOf(
            FocusPresenceInterval(
                Instant.fromEpochMilliseconds(10_000L),
                Instant.fromEpochMilliseconds(70_000L),
            ),
        )
        prefs.saveFocusSession(dayKey = 42L, intervals = intervals)
        assertEquals(42L to intervals, prefs.loadFocusSession())
    }

    @Test
    fun loadFocusSessionDefaultsToEmpty() = runTest {
        val prefs = newDesktopPreferences()
        assertEquals(-1L to emptyList<FocusPresenceInterval>(), prefs.loadFocusSession())
    }
```

If the file has no `newDesktopPreferences()` helper, mirror exactly how its existing tests instantiate `DesktopPreferences` (temp file + `PreferenceDataStoreFactory`). Ensure imports: `import kotlin.time.Instant`, `import kotlinx.coroutines.test.runTest`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DesktopDataStoreTest"`
Expected: FAIL — `saveFocusSession` / `loadFocusSession` unresolved.

- [ ] **Step 3: Add keys and methods**

In `DesktopPreferences.kt`, add after line 24 (the presence keys):

```kotlin
private const val KEY_FOCUS_SESSION_DAY = "focus_session_day"
private const val KEY_FOCUS_SESSION = "focus_session"
private val focusSessionDayKey = longPreferencesKey(KEY_FOCUS_SESSION_DAY)
private val focusSessionKey = stringPreferencesKey(KEY_FOCUS_SESSION)
```

Add after `saveFocusPresence` (after line 52), inside the class:

```kotlin

    // Session-focus (engaged-time) intervals: the lenient twin of focus presence, same
    // desktop-only DataStore treatment, kept outside the shared snapshot.
    suspend fun loadFocusSession(): Pair<Long, List<FocusPresenceInterval>> {
        val prefs = dataStore.data.first()
        val day = prefs[focusSessionDayKey] ?: -1L
        return day to decodeFocusPresence(prefs[focusSessionKey].orEmpty())
    }

    suspend fun saveFocusSession(dayKey: Long, intervals: List<FocusPresenceInterval>) {
        dataStore.edit {
            it[focusSessionDayKey] = dayKey
            it[focusSessionKey] = encodeFocusPresence(intervals)
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DesktopDataStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopPreferences.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/DesktopDataStoreTest.kt
git commit -m "Persist session-focus intervals in desktop preferences"
```

---

### Task 5: App wiring + UI readout (engaged figure)

Thread `focusSessionIntervals` through `DayViewApp` into the controller, and render the engaged duration next to the strict focus figure in `CountdownCircle`.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (`DayViewApp` param ~line 59, controller construction ~line 100, `LaunchedEffect` ~line 151)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`CountdownCircle` signature ~line 764-770, interior readout ~line 1147-1156, finished-day recap ~line 1176-1185, and the two `CountdownCircle(...)` call sites at ~line 273 and ~line 336)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/HistoryDayScreen.kt` (`CountdownCircle(...)` call ~line 47-48)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` (add `EngagedRecap`)
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` and `.../values-fr/strings.xml` (add `engaged_today`)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/CompletedDayTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.sessionFocusedToday` (Task 2), `DayViewController.setFocusSessionIntervals` + `initialFocusSessionIntervals` (Task 2).
- Produces:
  - `DayViewApp(..., focusSessionIntervals: List<FocusPresenceInterval> = emptyList(), ...)`
  - `CountdownCircle(..., sessionFocusedToday: Duration = Duration.ZERO, ...)`
  - `DayViewTestTags.EngagedRecap = "engagedRecap"`

- [ ] **Step 1: Write the failing UI test**

Add to `CompletedDayTest.kt`:

```kotlin
    @Test
    fun rendersEngagedRecapWhenFinishedWithSessionTime() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    focusedToday = 90.minutes,
                    sessionFocusedToday = 120.minutes,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.EngagedRecap).assertExists()
    }

    @Test
    fun hidesEngagedRecapWhenFinishedWithoutSessionTime() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    focusedToday = 90.minutes,
                    sessionFocusedToday = kotlin.time.Duration.ZERO,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.EngagedRecap).assertDoesNotExist()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CompletedDayTest"`
Expected: FAIL — `sessionFocusedToday` parameter and `EngagedRecap` tag unresolved.

- [ ] **Step 3: Add the test tag and string resources**

In `DayViewTestTags.kt`, after `const val FocusRecap = "focusRecap"` (line 14):

```kotlin
    const val EngagedRecap = "engagedRecap"
```

In `composeApp/src/commonMain/composeResources/values/strings.xml`, after the `focused_today` line:

```xml
    <string name="engaged_today">Engaged %1$s</string>
```

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, after the `focused_today` line:

```xml
    <string name="engaged_today">Engagé %1$s</string>
```

- [ ] **Step 4: Add the parameter and readout to `CountdownCircle`**

In `DayViewTodayScreen.kt`, add the parameter after `focusedToday: Duration = Duration.ZERO,` (line 770):

```kotlin
    sessionFocusedToday: Duration = Duration.ZERO,
```

Add the `engaged_today` import near the other generated-resource imports (next to `focused_today`):

```kotlin
import fr.dayview.app.generated.resources.engaged_today
```

In the interior readout, immediately after the focus `Text(...)` block inside `if (interior.showFocus) { ... }` (after line 1156's closing `)`), add:

```kotlin
                                if (sessionFocusedToday > Duration.ZERO) {
                                    Spacer(Modifier.height(6.dp * counterScale))
                                    Text(
                                        stringResource(Res.string.engaged_today, formatDurationHm(sessionFocusedToday)),
                                        color = colors.mint,
                                        fontSize = (13 * counterScale).sp,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = (.5f * counterScale).sp,
                                    )
                                }
```

In the finished-day recap branch, immediately after the focus recap `Text(...)` that carries `Modifier.testTag(DayViewTestTags.FocusRecap)` (after line 1185's closing `)`), add:

```kotlin
                            if (sessionFocusedToday > Duration.ZERO) {
                                Spacer(Modifier.height(6.dp * counterScale))
                                Text(
                                    stringResource(Res.string.engaged_today, formatDurationHm(sessionFocusedToday)),
                                    color = colors.mint,
                                    fontSize = (13 * counterScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (.5f * counterScale).sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag(DayViewTestTags.EngagedRecap),
                                )
                            }
```

Pass the value at the three call sites. In `DayViewTodayScreen.kt` at the two `CountdownCircle(...)` calls (~line 273 and ~line 336), add after `focusedToday = state.focusedToday,`:

```kotlin
                            sessionFocusedToday = state.sessionFocusedToday,
```

In `HistoryDayScreen.kt` after `focusedToday = state.focusedToday,` (line 48):

```kotlin
            sessionFocusedToday = state.sessionFocusedToday,
```

- [ ] **Step 5: Wire `DayViewApp` → controller**

In `App.kt`, add the parameter after `focusPresenceIntervals: List<FocusPresenceInterval> = emptyList(),` (line 59):

```kotlin
    focusSessionIntervals: List<FocusPresenceInterval> = emptyList(),
```

In the `DayViewController(...)` construction (after line 100 `initialFocusPresenceIntervals = focusPresenceIntervals,`):

```kotlin
                            initialFocusSessionIntervals = focusSessionIntervals,
```

Add a `LaunchedEffect` after the existing `focusPresenceIntervals` one (after line 153):

```kotlin
                    LaunchedEffect(focusSessionIntervals) {
                        controller.setFocusSessionIntervals(focusSessionIntervals)
                    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CompletedDayTest"`
Expected: PASS (both new cases plus the unchanged FocusRecap cases).

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt composeApp/src/commonMain/kotlin/fr/dayview/app/HistoryDayScreen.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-fr/strings.xml composeApp/src/desktopTest/kotlin/fr/dayview/app/CompletedDayTest.kt
git commit -m "Surface engaged-time figure alongside focus time"
```

---

### Task 6: Desktop loop integration

Run the lenient accumulator in the desktop ticker, persist it, and feed it into `DayViewApp`. This is the desktop entry glue (`@Composable main`), verified by the build + a manual run rather than a unit test.

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt` (accumulator setup ~line 118-126, the per-tick presence block ~line 191-208, the `DayViewApp(...)` call ~line 356)

**Interfaces:**
- Consumes: `PresenceAccumulator` (Task 1), `DesktopPreferences.loadFocusSession` / `saveFocusSession` (Task 4), `DayViewApp(focusSessionIntervals = ...)` (Task 5).
- Produces: none (entry wiring).

- [ ] **Step 1: Add the lenient accumulator, its state, and initial restore**

In `Main.kt`, after the `presenceAccumulator` / `focusPresenceIntervals` / `lastPresenceSave` block (after line 126), add:

```kotlin
    val initialFocusSession = remember { runBlocking { preferences.loadFocusSession() } }
    val sessionAccumulator = remember {
        PresenceAccumulator(
            presentStates = setOf(OnGoalState.ON_GOAL, OnGoalState.NEUTRAL),
            bridge = 120.seconds,
            minInterval = 60.seconds,
            interruptionGap = 15.seconds,
        ).also {
            val (day, intervals) = initialFocusSession
            if (day >= 0) it.restore(intervals, day)
        }
    }
    var focusSessionIntervals by remember { mutableStateOf(initialFocusSession.second) }
    var lastSessionSave by remember { mutableStateOf(Instant.DISTANT_PAST) }
```

Ensure the import `import kotlin.time.Duration.Companion.seconds` is present in `Main.kt` (add it if not).

- [ ] **Step 2: Feed and persist the lenient accumulator each tick**

In the per-tick loop, directly after the existing presence persistence block (after line 208, the `if (updatedIntervals != focusPresenceIntervals) { ... }` block), add the twin, reusing the same `classification`, `dayKey`, and `wasFocusActive` already computed above:

```kotlin
            val updatedSession = when {
                focusIsActive -> sessionAccumulator.observe(currentNow, classification, dayKey)
                wasFocusActive -> sessionAccumulator.endSession()
                else -> focusSessionIntervals
            }
            if (updatedSession != focusSessionIntervals) {
                val sessionStructuralChange = updatedSession.size != focusSessionIntervals.size
                focusSessionIntervals = updatedSession
                if (sessionStructuralChange || currentNow - lastSessionSave >= 30.seconds) {
                    preferences.saveFocusSession(dayKey, updatedSession)
                    lastSessionSave = currentNow
                }
            }
```

This must sit before `wasFocusActive = focusIsActive` (line 209) so both accumulators observe the same `wasFocusActive` edge.

- [ ] **Step 3: Pass the intervals into `DayViewApp`**

In the `DayViewApp(...)` call, after `focusPresenceIntervals = focusPresenceIntervals,` (line 356):

```kotlin
                focusSessionIntervals = focusSessionIntervals,
```

- [ ] **Step 4: Build the desktop app and run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint errors, all tests green.

- [ ] **Step 5: Manual verification (desktop entry has no unit harness)**

Run: `./gradlew :composeApp:run`
Then: start a focus session; spend part of it on an on-goal app and part on a non-listed (off-goal) app and on DayView itself (neutral). Confirm:
1. The "Engaged" figure appears and is **≥** the "Focus" figure.
2. A brief (<2 min) off-goal excursion does not stop the engaged figure growing, but a sustained (>2 min) off-goal stretch does.
3. Quit and relaunch during/after the session: both figures restore (persistence round-trips).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt
git commit -m "Accumulate and persist engaged time in the desktop focus loop"
```

---

## Notes for the implementer

- **Read order independence:** every task's new symbols are declared in the "Interfaces / Produces" block of the task that introduces them. Task 5 and 6 depend on Task 2's `sessionFocusedToday` / `setFocusSessionIntervals` and Task 4's persistence methods — implement 1→6 in order.
- **New required constructor fields** (`DayHistoryRecord.focusSessionIntervals` in Task 3) will break any test or code that constructs the record positionally/exhaustively; the compiler points to each site — add `focusSessionIntervals = emptyList()` there.
- **Android** shares `DayViewApp` but never sets `focusSessionIntervals` (defaults to empty), so the engaged figure simply never shows there — same as the strict presence figure today. No Android changes.
- **Sync stays untouched** by design (desktop-local, unsynced) — do not add fields to `SyncDocument`/`SyncMapper`.
