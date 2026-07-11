# Global Goal Progress Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a time-elapsed progress bar to the "OBJECTIF GLOBAL" panel, driven by working hours between an (editable) start instant and the deadline.

**Architecture:** A pure `calculateGoalProgress` reuses the existing `calculateGoalWorkingMillis` logic. A new persisted `goalStartMillis` anchors 0 %, defaulting to "now" when the deadline is committed and editable via a "Début" field. The panel renders a thin animated bar plus the elapsed percentage.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime, kotlin.test.

## Global Constraints

- French UI copy (match existing tone, e.g. "OBJECTIF GLOBAL", "Encore X h").
- Reuse `calculateGoalWorkingMillis` / `parseGoalDeadline` / `formatGoalDeadline` / `formatGoalDeadlineInput` — do not reimplement date logic.
- Persistence sentinel for "no value" is `NO_DEADLINE = -1L` (existing convention).
- Feature is common code; platform prefs are Android + Desktop; `DefaultDayPreferences` is the no-op stub.
- Out of scope (YAGNI): manual completion, Android widget bar, mini-app bar.

---

### Task 1: Progress calculation (`calculateGoalProgress`)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/GlobalGoal.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/GlobalGoalTest.kt`

**Interfaces:**
- Consumes: existing `calculateGoalWorkingMillis(now, deadline, startMinutesOfDay, endMinutesOfDay, timeZone)`.
- Produces: `fun calculateGoalProgress(nowMillis: Long, startMillis: Long, deadlineMillis: Long, startMinutesOfDay: Int, endMinutesOfDay: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()): Float` returning `0f..1f`.

- [ ] **Step 1: Write the failing tests**

Add to `GlobalGoalTest.kt` (the file already imports `LocalDateTime`, `TimeZone`, `toInstant`, `assertEquals`; add `import kotlinx.datetime.toInstant` is already present). Add a private helper and four tests inside the class:

```kotlin
    private fun millis(iso: String): Long =
        LocalDateTime.parse(iso).toInstant(zone).toEpochMilliseconds()

    @Test
    fun goalProgressIsZeroBeforeAndAtTheStart() {
        val start = millis("2026-01-05T08:00")
        val deadline = millis("2026-01-05T18:00")
        assertEquals(0f, calculateGoalProgress(start, start, deadline, 8 * 60, 18 * 60, zone))
        val before = millis("2026-01-04T20:00")
        assertEquals(0f, calculateGoalProgress(before, start, deadline, 8 * 60, 18 * 60, zone))
    }

    @Test
    fun goalProgressReachesHalfwayAcrossOneWorkingDay() {
        val start = millis("2026-01-05T08:00")
        val now = millis("2026-01-05T13:00")
        val deadline = millis("2026-01-05T18:00")
        assertEquals(0.5f, calculateGoalProgress(now, start, deadline, 8 * 60, 18 * 60, zone), 0.001f)
    }

    @Test
    fun goalProgressIsFullAtOrAfterTheDeadline() {
        val start = millis("2026-01-05T08:00")
        val deadline = millis("2026-01-05T18:00")
        assertEquals(1f, calculateGoalProgress(deadline, start, deadline, 8 * 60, 18 * 60, zone))
        val after = millis("2026-01-06T09:00")
        assertEquals(1f, calculateGoalProgress(after, start, deadline, 8 * 60, 18 * 60, zone))
    }

    @Test
    fun goalProgressIsZeroWhenStartEqualsDeadline() {
        val moment = millis("2026-01-05T12:00")
        assertEquals(0f, calculateGoalProgress(moment - 1, moment, moment, 8 * 60, 18 * 60, zone))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.GlobalGoalTest"`
Expected: FAIL — unresolved reference `calculateGoalProgress`.

- [ ] **Step 3: Implement `calculateGoalProgress`**

Append to `GlobalGoal.kt` (after `calculateGoalWorkingMillis`):

```kotlin
fun calculateGoalProgress(
    nowMillis: Long,
    startMillis: Long,
    deadlineMillis: Long,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Float {
    if (nowMillis >= deadlineMillis) return 1f
    val total = calculateGoalWorkingMillis(startMillis, deadlineMillis, startMinutesOfDay, endMinutesOfDay, timeZone)
    if (total <= 0L) return 0f
    val effectiveNow = maxOf(nowMillis, startMillis)
    val remaining = calculateGoalWorkingMillis(effectiveNow, deadlineMillis, startMinutesOfDay, endMinutesOfDay, timeZone)
    val elapsed = total - remaining
    return (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.GlobalGoalTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/GlobalGoal.kt composeApp/src/commonTest/kotlin/fr/dayview/app/GlobalGoalTest.kt
git commit -m "feat: add calculateGoalProgress for global goal"
```

---

### Task 2: Persist `goalStartMillis` and default it on deadline commit

This is the smallest compile-coherent unit: changing `saveGlobalGoal`'s signature forces every implementer and both controller call sites to change together.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt`
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt`
- Modify (test double): `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`
- Test: `composeApp/src/androidUnitTest/kotlin/fr/dayview/app/AndroidDayPreferencesTest.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DesktopDayPreferencesTest.kt`

**Interfaces:**
- Produces:
  - `DayPreferencesSnapshot.goalStartMillis: Long?` (default `null`)
  - `DayPreferences.loadGoalStartMillis(): Long?`
  - `DayPreferences.saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?)` (replaces the 2-arg version)
  - `DayViewUiState.goalStartMillis: Long?` and `DayViewUiState.goalStartText: String`
  - `DayViewController.commitGoalDeadline()` now sets `goalStartMillis = nowMillis` when a deadline is committed and no start exists; clears it when the deadline is cleared.

- [ ] **Step 1: Write the failing tests**

Add to `DayViewControllerTest.kt`:

```kotlin
    @Test
    fun committingADeadlineDefaultsTheStartToNow() {
        val preferences = InMemoryDayPreferences()
        val controller = DayViewController(preferences, initialNowMillis = 5_000L)

        controller.setGoalDeadlineText("24/12/2026 18:30")
        controller.commitGoalDeadline()

        assertEquals(5_000L, controller.state.goalStartMillis)
        assertEquals(5_000L, preferences.current.goalStartMillis)
    }

    @Test
    fun clearingTheDeadlineClearsTheStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = 1_000L),
        )
        val controller = DayViewController(preferences, initialNowMillis = 5_000L)

        controller.setGoalDeadlineText("")
        controller.commitGoalDeadline()

        assertEquals(null, controller.state.goalStartMillis)
        assertEquals(null, preferences.current.goalStartMillis)
    }

    @Test
    fun editingAnExistingDeadlineKeepsTheStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = 1_000L),
        )
        val controller = DayViewController(preferences, initialNowMillis = 5_000L)

        controller.setGoalDeadlineText("26/12/2026 18:30")
        controller.commitGoalDeadline()

        assertEquals(1_000L, controller.state.goalStartMillis)
    }
```

Update the test double `InMemoryDayPreferences` (bottom of the same file) — replace its `saveGlobalGoal` and add `loadGoalStartMillis`:

```kotlin
    override fun loadGoalStartMillis(): Long? = current.goalStartMillis

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?) {
        current = current.copy(goalTitle = title, goalDeadlineMillis = deadlineMillis, goalStartMillis = startMillis)
        emit()
    }
```

- [ ] **Step 2: Run tests to verify they fail (compile error is expected)**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `goalStartMillis` unresolved / `saveGlobalGoal` signature mismatch until the code below lands.

- [ ] **Step 3: Add the snapshot field and interface members**

In `DayPreferences.kt`:
- Add to `DayPreferencesSnapshot` after `goalDeadlineMillis`:

```kotlin
    val goalStartMillis: Long? = null,
```

- In the `DayPreferences` interface, replace `fun saveGlobalGoal(title: String, deadlineMillis: Long?)` with:

```kotlin
    fun loadGoalStartMillis(): Long?
    fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?)
```

- In `snapshot()`, add after `goalDeadlineMillis = loadGoalDeadlineMillis(),`:

```kotlin
        goalStartMillis = loadGoalStartMillis(),
```

- In `DefaultDayPreferences`, replace the `saveGlobalGoal` override and add the loader:

```kotlin
    override fun loadGoalStartMillis(): Long? = null
    override fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?) = Unit
```

- [ ] **Step 4: Update Android and Desktop preferences**

In `AndroidDayPreferences.kt`, add the loader after `loadGoalDeadlineMillis` and replace `saveGlobalGoal`:

```kotlin
    override fun loadGoalStartMillis(): Long? = storage.getLong(KEY_GOAL_START, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?) {
        storage.edit()
            .putString(KEY_GOAL_TITLE, title)
            .putLong(KEY_GOAL_DEADLINE, deadlineMillis ?: NO_DEADLINE)
            .putLong(KEY_GOAL_START, startMillis ?: NO_DEADLINE)
            .apply()
        refreshWidgets()
    }
```

Add the key constant next to `KEY_GOAL_DEADLINE`:

```kotlin
        const val KEY_GOAL_START = "goal_start"
```

In `DesktopDayPreferences.kt`, add the loader after `loadGoalDeadlineMillis` and replace `saveGlobalGoal`:

```kotlin
    override fun loadGoalStartMillis(): Long? = storage.getLong(KEY_GOAL_START, NO_DEADLINE).takeUnless { it == NO_DEADLINE }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?) {
        storage.put(KEY_GOAL_TITLE, title)
        storage.putLong(KEY_GOAL_DEADLINE, deadlineMillis ?: NO_DEADLINE)
        storage.putLong(KEY_GOAL_START, startMillis ?: NO_DEADLINE)
        preferencesChanged()
    }
```

Add the key constant next to `KEY_GOAL_DEADLINE`:

```kotlin
        const val KEY_GOAL_START = "goal_start"
```

- [ ] **Step 5: Wire the controller state and default-start logic**

In `DayViewController.kt`:
- Add to `DayViewUiState` after `val goalDeadlineMillis: Long?,`:

```kotlin
    val goalStartText: String,
    val goalStartMillis: Long?,
```

- In `toUiState`, add after `goalDeadlineMillis = safe.goalDeadlineMillis,`:

```kotlin
        goalStartText = safe.goalStartMillis?.let(::formatGoalDeadline).orEmpty(),
        goalStartMillis = safe.goalStartMillis,
```

- In `withPersisted`, add after `goalDeadlineMillis = safe.goalDeadlineMillis,`:

```kotlin
        goalStartMillis = safe.goalStartMillis,
```

  and extend the transient-fields comment to mention `goalStartText`.

- In `setGoalTitle`, change the save call to:

```kotlin
        preferences.saveGlobalGoal(updated, state.goalDeadlineMillis, state.goalStartMillis)
```

- Replace `commitGoalDeadline` with:

```kotlin
    fun commitGoalDeadline() {
        val parsed = parseGoalDeadline(state.goalDeadlineText)
        if (parsed == null && state.goalDeadlineText.isNotBlank()) return
        val start = when {
            parsed == null -> null
            state.goalStartMillis == null -> state.nowMillis
            else -> state.goalStartMillis
        }
        state = state.copy(
            goalDeadlineMillis = parsed,
            goalStartMillis = start,
            goalStartText = start?.let(::formatGoalDeadline).orEmpty(),
        )
        preferences.saveGlobalGoal(state.goalTitle, parsed, start)
    }
```

- [ ] **Step 6: Add the persistence round-trip tests**

In `AndroidDayPreferencesTest.kt`, extend the existing save/reload test: after `preferences.saveGlobalGoal("Livrer DayView", 1_800_000_000_000L)` becomes `preferences.saveGlobalGoal("Livrer DayView", 1_800_000_000_000L, 1_700_000_000_000L)`, and add after the deadline assertion:

```kotlin
        assertEquals(1_700_000_000_000L, reloaded.loadGoalStartMillis())
```

Also update the defaults test to assert `assertNull(preferences.loadGoalStartMillis())`.

In `DesktopDayPreferencesTest.kt`:
- Update `freshStorageUsesExpectedDefaults` to assert `assertNull(preferences.loadGoalStartMillis())`.
- In `globalGoalSurvivesANewPreferencesInstance`, change the save call to `preferences.saveGlobalGoal("Livrer DayView", 1_800_000_000_000L, 1_700_000_000_000L)` and assert `assertEquals(1_700_000_000_000L, reloaded.loadGoalStartMillis())`.
- In `clearingDeadlineKeepsTitleAndPersistsNull`, change both save calls to the 3-arg form (`..., 1_700_000_000_000L)` then `..., null, null)`) and assert `assertNull(reloaded.loadGoalStartMillis())`.

- [ ] **Step 7: Run the affected tests**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest" --tests "fr.dayview.app.DesktopDayPreferencesTest" && ./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.AndroidDayPreferencesTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt composeApp/src/androidUnitTest/kotlin/fr/dayview/app/AndroidDayPreferencesTest.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/DesktopDayPreferencesTest.kt
git commit -m "feat: persist goalStartMillis and default it on deadline commit"
```

---

### Task 3: Editable start (`setGoalStartText` / `commitGoalStart`)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.goalDeadlineMillis`, `goalStartMillis`, `goalStartText`; `parseGoalDeadline`.
- Produces: `DayViewController.setGoalStartText(value: String)` and `DayViewController.commitGoalStart()`. A start is persisted only when it parses and is strictly before the deadline.

- [ ] **Step 1: Write the failing tests**

Add to `DayViewControllerTest.kt`:

```kotlin
    @Test
    fun commitGoalStartPersistsAValidEarlierStart() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = 5_000L),
        )
        val controller = DayViewController(preferences, initialNowMillis = 5_000L)

        controller.setGoalStartText("01/12/2026 09:00")
        controller.commitGoalStart()

        val expected = parseGoalDeadline("01/12/2026 09:00")!!
        assertEquals(expected, controller.state.goalStartMillis)
        assertEquals(expected, preferences.current.goalStartMillis)
    }

    @Test
    fun commitGoalStartRejectsAStartOnOrAfterTheDeadline() {
        val deadline = parseGoalDeadline("24/12/2026 18:30")!!
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(goalDeadlineMillis = deadline, goalStartMillis = 5_000L),
        )
        val controller = DayViewController(preferences, initialNowMillis = 5_000L)

        controller.setGoalStartText("25/12/2026 09:00")
        controller.commitGoalStart()

        assertEquals(5_000L, controller.state.goalStartMillis)
        assertEquals(5_000L, preferences.current.goalStartMillis)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — unresolved `setGoalStartText` / `commitGoalStart`.

- [ ] **Step 3: Implement the actions**

In `DayViewController.kt`, add after `commitGoalDeadline`:

```kotlin
    fun setGoalStartText(value: String) {
        state = state.copy(goalStartText = value.take(16))
    }

    fun commitGoalStart() {
        val deadline = state.goalDeadlineMillis ?: return
        val parsed = parseGoalDeadline(state.goalStartText) ?: return
        if (parsed >= deadline) return
        state = state.copy(goalStartMillis = parsed)
        preferences.saveGlobalGoal(state.goalTitle, deadline, parsed)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "feat: allow editing the global goal start date"
```

---

### Task 4: Progress bar and "Début" field in the panel

Presentational wiring; verified by build (no Compose UI tests exist for this screen).

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`

**Interfaces:**
- Consumes: `calculateGoalProgress` (Task 1); `DayViewUiState.goalStartText` / `goalStartMillis` (Task 2); `controller.setGoalStartText` / `controller.commitGoalStart` (Task 3).
- Produces: two new fields on `DayViewScreenActions` (`changeGoalStart: (String) -> Unit`, `commitGoalStart: () -> Unit`) and four new `GlobalGoalPanel` parameters (`startText`, `startMillis`, `onStartChange`, `onStartCommit`).

- [ ] **Step 1: Add the actions and wire them in App.kt**

In `DayViewTodayScreen.kt`, add to `DayViewScreenActions` after `commitGoalDeadline`:

```kotlin
    val changeGoalStart: (String) -> Unit,
    val commitGoalStart: () -> Unit,
```

In `App.kt`, in the `DayViewScreenActions(...)` construction, add after `commitGoalDeadline = { controller.commitGoalDeadline() },`:

```kotlin
                        changeGoalStart = { controller.setGoalStartText(it) },
                        commitGoalStart = { controller.commitGoalStart() },
```

- [ ] **Step 2: Add missing imports**

In `DayViewTodayScreen.kt`, add these imports (grouped with existing ones):

```kotlin
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import kotlin.math.roundToInt
```

- [ ] **Step 3: Extend `GlobalGoalPanel` signature and both call sites**

Add four parameters to `GlobalGoalPanel` after `deadlineMillis: Long?,`:

```kotlin
    startText: String,
    startMillis: Long?,
```

and after `onDeadlineCommit: () -> Unit,`:

```kotlin
    onStartChange: (String) -> Unit,
    onStartCommit: () -> Unit,
```

At BOTH `GlobalGoalPanel(...)` call sites (the wide layout ~line 129 and the compact layout ~line 161), add after `deadlineMillis = state.goalDeadlineMillis,`:

```kotlin
                            startText = state.goalStartText,
                            startMillis = state.goalStartMillis,
```

and after `onDeadlineCommit = actions.commitGoalDeadline,`:

```kotlin
                            onStartChange = actions.changeGoalStart,
                            onStartCommit = actions.commitGoalStart,
```

(Match the indentation of each call site.)

- [ ] **Step 4: Render the bar and the Début field**

In `GlobalGoalPanel`, insert this block after the `BoxWithConstraints { ... }` that holds the title/deadline fields and before the `if (!deadlineIsValid) { ... }` block:

```kotlin
        if (deadlineMillis != null && startMillis != null) {
            val progress = remember(
                nowMillis / 60_000,
                startMillis,
                deadlineMillis,
                workStartMinutes,
                workEndMinutes,
            ) {
                calculateGoalProgress(
                    nowMillis = nowMillis,
                    startMillis = startMillis,
                    deadlineMillis = deadlineMillis,
                    startMinutesOfDay = workStartMinutes,
                    endMinutesOfDay = workEndMinutes,
                )
            }
            val animatedProgress by animateFloatAsState(progress, tween(650), label = "goal-progress")
            val startIsValid = startText.isBlank() ||
                (parseGoalDeadline(startText)?.let { it < deadlineMillis } ?: false)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.overlay.copy(alpha = .12f)),
                ) {
                    Box(
                        modifier = Modifier.fillMaxHeight()
                            .fillMaxWidth(animatedProgress)
                            .background(colors.mint, RoundedCornerShape(3.dp)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${(progress * 100).roundToInt()} %",
                    color = colors.muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(10.dp))
            GoalTextField(
                value = startText,
                semanticLabel = "Début de l’objectif",
                placeholder = GOAL_DATE_PLACEHOLDER,
                onValueChange = { onStartChange(formatGoalDeadlineInput(it)) },
                isError = !startIsValid,
                keyboardType = KeyboardType.Number,
                onFocusLost = onStartCommit,
                modifier = Modifier.width(148.dp),
            )
        }
```

- [ ] **Step 5: Build to verify it compiles**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "feat: show global goal progress bar and editable start"
```

---

### Task 5: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full common + desktop test suite**

Run: `./gradlew :composeApp:desktopTest`
Expected: PASS.

- [ ] **Step 2: Run the Android unit tests**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 3: Lint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (fix any formatting issues it reports, then re-run).

- [ ] **Step 4: Drive the app (verify skill)**

Use the `verify` / `run` skill to launch the desktop app, set a goal with a deadline, and confirm the progress bar and percentage render and that editing "Début" updates the bar. Fix any issues found before completing.

## Self-Review notes

- Spec coverage: data model + persistence (Task 2), start rule + editable start (Tasks 2–3), progress calc (Task 1), UI bar + Début field (Task 4), tests (Tasks 1–3 + Task 5). All spec sections mapped.
- Naming consistency: `calculateGoalProgress`, `goalStartMillis`, `goalStartText`, `setGoalStartText`, `commitGoalStart`, `KEY_GOAL_START`, and the `saveGlobalGoal(title, deadlineMillis, startMillis)` signature are used identically across tasks.
- Note vs. spec: the spec named the stub `NoopDayPreferences`; the actual stub is `DefaultDayPreferences` — this plan uses the real name.
