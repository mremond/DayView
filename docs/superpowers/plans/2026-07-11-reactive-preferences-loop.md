# Reactive Preferences Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Android `MainActivity.recreate()` hack with a live, in-place reactive loop so external preference writes (Quick Settings tile, focus notification actions) update the open UI without destroying transient UI state.

**Architecture:** `DayViewController` becomes the single owner of a Compose-observable `DayViewUiState`. Its setters update in-memory state first, then persist. A new `onPreferencesChanged(snapshot)` merges persisted fields from an external write into the current state while preserving transient/UI-only fields. `App.kt` subscribes the controller to `DayPreferences.observe()`. On Android, `observe()` is reimplemented on top of a process-wide `SharedPreferences.OnSharedPreferenceChangeListener` so cross-component writes propagate.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (`compose.runtime`, already a `commonMain` dependency), Android `SharedPreferences`, Robolectric (Android unit tests), kotlin.test (common tests).

## Global Constraints

- JVM toolchain: 21 (already configured). Reasoning: Robolectric needs Java 21 for `compileSdk` 36.
- No new dependencies. `compose.runtime` (for `mutableStateOf`) is already in `commonMain`.
- Do not modify `DesktopDayPreferences` or `Main.kt` — desktop already subscribes via `observe()` and only the controller writes there.
- Keep ktlint clean: run `./gradlew ktlintFormat` before the final commit; official style, `@Composable` naming exception, generated sources excluded (already configured in `.editorconfig`).
- Green gate command: `./gradlew ktlintCheck testDebugUnitTest desktopTest assembleDebug assembleRelease`.
- The Android worktree needs `local.properties` with `sdk.dir` (already present; untracked). If missing, copy from the main checkout.

---

## File Structure

- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` — rewritten: controller owns `mutableStateOf` state; setters return `Unit` (update-first, persist-second); adds `onPreferencesChanged`; shared coercion helper.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` — rewired: reads `controller.state`, subscribes via `observe()`, drops the local state mirror and per-action reassignments.
- `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt` — extended: reconciliation tests; `InMemoryDayPreferences` emits to observers on save; one lifecycle test updated for `Unit`-returning `startPomodoro`.
- `composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt` — `observe()` reimplemented on `OnSharedPreferenceChangeListener` with snapshot dedup; in-memory observer map removed; widget refresh kept.
- `composeApp/src/androidUnitTest/kotlin/fr/dayview/app/AndroidDayPreferencesTest.kt` — new cross-instance propagation test.
- `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt` — remove `recreate()` machinery.

---

## Task 1: Reactive core in commonMain (controller + App wiring)

This task is atomic for compilation: changing the controller's setter return types requires rewiring `App.kt` in the same task, or the desktop build breaks.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (full rewrite of the class body and helpers)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt:31-133`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `DayPreferences` (`snapshot()`, `observe(observer): () -> Unit`, `saveX(...)`), `DayPreferencesSnapshot`, `DayViewUiState`, `parseGoalDeadline`, `formatGoalDeadline`, `focusIntentionAfterClosure`, `SoundSettings.normalized()`.
- Produces:
  - `DayViewController.state: DayViewUiState` — a Compose `mutableStateOf`-backed property, `private set`.
  - Setters now return `Unit`: `tick(Long)`, `openSettings()`, `openToday()`, `setStartMinutes(Int)`, `setEndMinutes(Int)`, `setShowSeconds(Boolean)`, `setSoundSettings(SoundSettings)`, `setGoalTitle(String)`, `setGoalDeadlineText(String)`, `commitGoalDeadline()`, `setFocusIntention(String)`, `changePomodoroDuration(Int)`, `startPomodoro()`, `stopPomodoro()`, `closePomodoro(FocusClosureOutcome)`.
  - New: `onPreferencesChanged(snapshot: DayPreferencesSnapshot)` returning `Unit`.

- [ ] **Step 1: Write the failing reconciliation tests**

Append these tests to `DayViewControllerTest.kt` (inside the class, before the closing brace of `class DayViewControllerTest`):

```kotlin
    @Test
    fun onPreferencesChangedAdoptsExternalPersistedFields() {
        val preferences = InMemoryDayPreferences()
        val controller = DayViewController(preferences, initialNowMillis = 10_000L)
        val focusEnd = 10_000L + 50 * 60_000L

        controller.onPreferencesChanged(
            DayPreferencesSnapshot(
                startMinutes = 9 * 60,
                endMinutes = 17 * 60,
                pomodoroMinutes = 50,
                pomodoroEndMillis = focusEnd,
                focusIntention = "Depuis la tuile",
            ),
        )

        assertEquals(9 * 60, controller.state.startMinutes)
        assertEquals(17 * 60, controller.state.endMinutes)
        assertEquals(focusEnd, controller.state.pomodoroEndMillis)
        assertEquals("Depuis la tuile", controller.state.focusIntention)
        assertEquals(true, controller.state.focusIsActive)
    }

    @Test
    fun onPreferencesChangedPreservesTransientUiState() {
        val preferences = InMemoryDayPreferences()
        val controller = DayViewController(preferences, initialNowMillis = 10_000L)
        controller.openSettings()
        controller.setGoalDeadlineText("25/12/2026 19:45")

        controller.onPreferencesChanged(
            DayPreferencesSnapshot(focusIntention = "Depuis la tuile", pomodoroEndMillis = 20_000L),
        )

        assertEquals(DayViewDestination.SETTINGS, controller.state.destination)
        assertEquals("25/12/2026 19:45", controller.state.goalDeadlineText)
        assertEquals(10_000L, controller.state.nowMillis)
        assertEquals("Depuis la tuile", controller.state.focusIntention)
    }

    @Test
    fun externalSaveReachesTheControllerThroughObserve() {
        val preferences = InMemoryDayPreferences()
        val controller = DayViewController(preferences, initialNowMillis = 10_000L)
        val stopObserving = preferences.observe(controller::onPreferencesChanged)

        preferences.savePomodoro(50, 1_800_000_000_000L)
        preferences.saveFocusIntention("Depuis la tuile")

        assertEquals(1_800_000_000_000L, controller.state.pomodoroEndMillis)
        assertEquals("Depuis la tuile", controller.state.focusIntention)

        stopObserving()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — compilation error `unresolved reference: onPreferencesChanged`.

- [ ] **Step 3: Rewrite `DayViewController.kt`**

Replace the entire file contents with:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.Clock

internal enum class DayViewDestination {
    TODAY,
    SETTINGS,
}

internal data class DayViewUiState(
    val nowMillis: Long,
    val startMinutes: Int,
    val endMinutes: Int,
    val showSeconds: Boolean,
    val soundSettings: SoundSettings,
    val goalTitle: String,
    val goalDeadlineText: String,
    val goalDeadlineMillis: Long?,
    val pomodoroMinutes: Int,
    val pomodoroEndMillis: Long?,
    val focusIntention: String,
    val lastFocusClosure: FocusClosureOutcome? = null,
    val destination: DayViewDestination = DayViewDestination.TODAY,
) {
    val dayProgress: DayProgress
        get() {
            val dayNowMillis = if (showSeconds) nowMillis else nowMillis - nowMillis % 60_000L
            return calculateDayProgress(dayNowMillis, startMinutes, endMinutes)
        }

    val pomodoroProgress: PomodoroProgress
        get() = calculatePomodoroProgress(nowMillis, pomodoroMinutes, pomodoroEndMillis)

    val focusIsActive: Boolean
        get() = pomodoroEndMillis?.let { it > nowMillis } == true
}

internal class DayViewController(
    private val preferences: DayPreferences,
    initialNowMillis: Long = Clock.System.now().toEpochMilliseconds(),
) {
    var state: DayViewUiState by mutableStateOf(preferences.snapshot().toUiState(initialNowMillis))
        private set

    fun tick(nowMillis: Long) {
        state = state.copy(nowMillis = nowMillis)
    }

    fun openSettings() {
        state = state.copy(destination = DayViewDestination.SETTINGS)
    }

    fun openToday() {
        state = state.copy(destination = DayViewDestination.TODAY)
    }

    fun setStartMinutes(minutes: Int) {
        val updated = minutes.coerceIn(0, state.endMinutes - 30)
        state = state.copy(startMinutes = updated)
        preferences.saveDayRange(updated, state.endMinutes)
    }

    fun setEndMinutes(minutes: Int) {
        val updated = minutes.coerceIn(state.startMinutes + 30, 23 * 60 + 59)
        state = state.copy(endMinutes = updated)
        preferences.saveDayRange(state.startMinutes, updated)
    }

    fun setShowSeconds(enabled: Boolean) {
        state = state.copy(showSeconds = enabled)
        preferences.saveShowSeconds(enabled)
    }

    fun setSoundSettings(settings: SoundSettings) {
        val normalized = settings.normalized()
        state = state.copy(soundSettings = normalized)
        preferences.saveSoundSettings(normalized)
    }

    fun setGoalTitle(value: String) {
        val updated = value.take(80)
        state = state.copy(goalTitle = updated)
        preferences.saveGlobalGoal(updated, state.goalDeadlineMillis)
    }

    fun setGoalDeadlineText(value: String) {
        state = state.copy(goalDeadlineText = value.take(16))
    }

    fun commitGoalDeadline() {
        val parsed = parseGoalDeadline(state.goalDeadlineText)
        if (parsed == null && state.goalDeadlineText.isNotBlank()) return
        state = state.copy(goalDeadlineMillis = parsed)
        preferences.saveGlobalGoal(state.goalTitle, parsed)
    }

    fun setFocusIntention(value: String) {
        val updated = value.take(100)
        state = state.copy(focusIntention = updated, lastFocusClosure = null)
        preferences.saveFocusIntention(updated)
    }

    fun changePomodoroDuration(deltaMinutes: Int) {
        if (state.pomodoroProgress.status == PomodoroStatus.ACTIVE) return
        val updated = (state.pomodoroMinutes + deltaMinutes).coerceIn(5, 180)
        state = state.copy(pomodoroMinutes = updated, pomodoroEndMillis = null)
        preferences.savePomodoro(updated, null)
    }

    fun startPomodoro() {
        if (state.focusIntention.isBlank()) return
        val endMillis = state.nowMillis + state.pomodoroMinutes * 60_000L
        state = state.copy(pomodoroEndMillis = endMillis, lastFocusClosure = null)
        preferences.savePomodoro(state.pomodoroMinutes, endMillis)
    }

    fun stopPomodoro() {
        state = state.copy(pomodoroEndMillis = null)
        preferences.savePomodoro(state.pomodoroMinutes, null)
    }

    fun closePomodoro(outcome: FocusClosureOutcome) {
        val updatedIntention = focusIntentionAfterClosure(state.focusIntention, outcome)
        val intentionChanged = updatedIntention != state.focusIntention
        state = state.copy(
            pomodoroEndMillis = null,
            focusIntention = updatedIntention,
            lastFocusClosure = outcome,
        )
        preferences.savePomodoro(state.pomodoroMinutes, null)
        if (intentionChanged) preferences.saveFocusIntention(updatedIntention)
    }

    fun onPreferencesChanged(snapshot: DayPreferencesSnapshot) {
        state = state.withPersisted(snapshot)
    }
}

private fun DayPreferencesSnapshot.coerced(): DayPreferencesSnapshot {
    val safeStart = startMinutes.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutes.coerceIn(safeStart + 30, 23 * 60 + 59)
    return copy(
        startMinutes = safeStart,
        endMinutes = safeEnd,
        soundSettings = soundSettings.normalized(),
        pomodoroMinutes = pomodoroMinutes.coerceIn(5, 180),
    )
}

private fun DayPreferencesSnapshot.toUiState(nowMillis: Long): DayViewUiState {
    val safe = coerced()
    return DayViewUiState(
        nowMillis = nowMillis,
        startMinutes = safe.startMinutes,
        endMinutes = safe.endMinutes,
        showSeconds = safe.showSeconds,
        soundSettings = safe.soundSettings,
        goalTitle = safe.goalTitle,
        goalDeadlineText = safe.goalDeadlineMillis?.let(::formatGoalDeadline).orEmpty(),
        goalDeadlineMillis = safe.goalDeadlineMillis,
        pomodoroMinutes = safe.pomodoroMinutes,
        pomodoroEndMillis = safe.pomodoroEndMillis,
        focusIntention = safe.focusIntention,
    )
}

private fun DayViewUiState.withPersisted(snapshot: DayPreferencesSnapshot): DayViewUiState {
    val safe = snapshot.coerced()
    return copy(
        startMinutes = safe.startMinutes,
        endMinutes = safe.endMinutes,
        showSeconds = safe.showSeconds,
        soundSettings = safe.soundSettings,
        goalTitle = safe.goalTitle,
        goalDeadlineMillis = safe.goalDeadlineMillis,
        pomodoroMinutes = safe.pomodoroMinutes,
        pomodoroEndMillis = safe.pomodoroEndMillis,
        focusIntention = safe.focusIntention,
        // Transient fields deliberately preserved: nowMillis, goalDeadlineText,
        // lastFocusClosure, destination.
    )
}
```

- [ ] **Step 4: Make `InMemoryDayPreferences` emit to observers on save, and fix the lifecycle test**

In `DayViewControllerTest.kt`, replace the `InMemoryDayPreferences` class (currently at the bottom of the file) with this version that notifies observers on every save:

```kotlin
private class InMemoryDayPreferences(
    initial: DayPreferencesSnapshot = DayPreferencesSnapshot(),
) : DayPreferences {
    var current: DayPreferencesSnapshot = initial
        private set

    private val observers = mutableListOf<(DayPreferencesSnapshot) -> Unit>()

    override fun observe(observer: (DayPreferencesSnapshot) -> Unit): () -> Unit {
        observers.add(observer)
        observer(current)
        return { observers.remove(observer) }
    }

    private fun emit() {
        observers.toList().forEach { it(current) }
    }

    override fun snapshot(): DayPreferencesSnapshot = current
    override fun loadStartMinutes(): Int = current.startMinutes
    override fun loadEndMinutes(): Int = current.endMinutes
    override fun loadShowSeconds(): Boolean = current.showSeconds
    override fun loadSoundSettings(): SoundSettings = current.soundSettings
    override fun loadGoalTitle(): String = current.goalTitle
    override fun loadGoalDeadlineMillis(): Long? = current.goalDeadlineMillis
    override fun loadPomodoroMinutes(): Int = current.pomodoroMinutes
    override fun loadPomodoroEndMillis(): Long? = current.pomodoroEndMillis
    override fun loadFocusIntention(): String = current.focusIntention

    override fun saveDayRange(startMinutes: Int, endMinutes: Int) {
        current = current.copy(startMinutes = startMinutes, endMinutes = endMinutes)
        emit()
    }

    override fun saveShowSeconds(showSeconds: Boolean) {
        current = current.copy(showSeconds = showSeconds)
        emit()
    }

    override fun saveSoundSettings(settings: SoundSettings) {
        current = current.copy(soundSettings = settings)
        emit()
    }

    override fun saveGlobalGoal(title: String, deadlineMillis: Long?) {
        current = current.copy(goalTitle = title, goalDeadlineMillis = deadlineMillis)
        emit()
    }

    override fun savePomodoro(durationMinutes: Int, endMillis: Long?) {
        current = current.copy(pomodoroMinutes = durationMinutes, pomodoroEndMillis = endMillis)
        emit()
    }

    override fun saveFocusIntention(intention: String) {
        current = current.copy(focusIntention = intention)
        emit()
    }
}
```

Then fix `controllerOwnsTheCompleteFocusLifecycle` — replace the two lines:

```kotlin
        val started = controller.startPomodoro()
        val expectedEnd = 10_000L + 25 * 60_000L
```

with:

```kotlin
        controller.startPomodoro()
        val started = controller.state
        val expectedEnd = 10_000L + 25 * 60_000L
```

- [ ] **Step 5: Rewrite `App.kt` body to read `controller.state` and subscribe via `observe()`**

Replace lines 31-133 (the `DayViewTheme { colors -> ... }` block) of `App.kt` with:

```kotlin
    DayViewTheme { colors ->
        Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
            val controller = remember(preferences) { DayViewController(preferences) }
            val state = controller.state
            val soundPlayer = remember { createSoundCuePlayer() }
            val soundScheduler = remember { SoundAlertScheduler() }

            DisposableEffect(controller, preferences) {
                val stopObserving = preferences.observe { controller.onPreferencesChanged(it) }
                onDispose(stopObserving)
            }
            PlatformBackHandler(enabled = state.destination == DayViewDestination.SETTINGS) {
                controller.openToday()
            }
            DisposableEffect(soundPlayer) {
                onDispose { soundPlayer.close() }
            }
            LaunchedEffect(state.showSeconds, state.pomodoroEndMillis) {
                while (true) {
                    val nowMillis = Clock.System.now().toEpochMilliseconds()
                    controller.tick(nowMillis)
                    val current = controller.state
                    val refreshDelay = if (current.showSeconds || current.pomodoroEndMillis != null) {
                        1_000L
                    } else {
                        60_000L - nowMillis % 60_000L
                    }
                    delay(refreshDelay)
                }
            }
            LaunchedEffect(
                state.nowMillis,
                state.startMinutes,
                state.endMinutes,
                state.soundSettings,
                scheduleSoundAlerts,
                state.focusIsActive,
            ) {
                if (scheduleSoundAlerts) {
                    val cue = soundScheduler.observe(
                        nowMillis = state.nowMillis,
                        startMinutesOfDay = state.startMinutes,
                        endMinutesOfDay = state.endMinutes,
                        intervalMinutes = state.soundSettings.intervalMinutes,
                    )
                    if (cue != null && state.soundSettings.allowsDayCue(cue, state.focusIsActive)) {
                        soundPlayer.play(cue, state.soundSettings.volumePercent / 100f)
                    }
                }
            }

            if (state.destination == DayViewDestination.SETTINGS) {
                SettingsScreen(
                    state = state,
                    platformState = SettingsPlatformUiState(
                        monochromeMenuBarIcon = monochromeMenuBarIcon,
                        launchAtLogin = launchAtLogin,
                    ),
                    actions = SettingsScreenActions(
                        changeStartTime = { controller.setStartMinutes(it) },
                        changeEndTime = { controller.setEndMinutes(it) },
                        changeShowSeconds = { controller.setShowSeconds(it) },
                        changeMonochromeMenuBarIcon = onMonochromeMenuBarIconChange,
                        changeLaunchAtLogin = onLaunchAtLoginChange,
                        changeSoundSettings = { controller.setSoundSettings(it) },
                        previewSound = { cue ->
                            soundPlayer.play(cue, controller.state.soundSettings.volumePercent / 100f)
                        },
                        back = { controller.openToday() },
                    ),
                )
            } else {
                DayViewScreen(
                    state = state,
                    actions = DayViewScreenActions(
                        openSettings = { controller.openSettings() },
                        openMiniWindow = onOpenMiniWindow,
                        changeGoalTitle = { controller.setGoalTitle(it) },
                        changeGoalDeadline = { controller.setGoalDeadlineText(it) },
                        commitGoalDeadline = { controller.commitGoalDeadline() },
                        changeFocusIntention = { controller.setFocusIntention(it) },
                        changePomodoroDuration = { controller.changePomodoroDuration(it) },
                        startPomodoro = {
                            controller.startPomodoro()
                            controller.state.pomodoroEndMillis?.let {
                                onFocusAlarmChange(it, controller.state.focusIntention)
                            }
                        },
                        stopPomodoro = {
                            val intention = controller.state.focusIntention
                            controller.stopPomodoro()
                            onFocusAlarmChange(null, intention)
                        },
                        closePomodoro = { outcome ->
                            val intention = controller.state.focusIntention
                            controller.closePomodoro(outcome)
                            onFocusAlarmChange(null, intention)
                        },
                    ),
                    reminders = FocusReminderUiState(
                        showDriftReminder = showFocusDriftReminder,
                        dismissDriftReminder = onDismissFocusDriftReminder,
                        showResumeRitual = showFocusResumeRitual,
                        dismissResumeRitual = onDismissFocusResumeRitual,
                    ),
                )
            }
        }
    }
```

Note: event-handler lambdas that read state *after* a mutation (the three focus actions, `previewSound`) read `controller.state` directly, because the composition-captured `val state` holds the pre-write snapshot. Render reads and `LaunchedEffect` keys use `val state` for Compose change tracking.

Then remove the now-unused imports from the top of `App.kt`: delete the lines `import androidx.compose.runtime.getValue`, `import androidx.compose.runtime.mutableStateOf`, and `import androidx.compose.runtime.setValue`. Keep `remember`, `DisposableEffect`, `LaunchedEffect`.

- [ ] **Step 6: Run the common tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS (all tests, including the three new ones).

Then run the full desktop test target to confirm `App.kt` still compiles for desktop and nothing else regressed:

Run: `./gradlew :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "feat: controller owns observable state and reconciles external changes"
```

---

## Task 2: Cross-component observe() on Android

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt`
- Test: `composeApp/src/androidUnitTest/kotlin/fr/dayview/app/AndroidDayPreferencesTest.kt`

**Interfaces:**
- Consumes: `DayPreferencesSnapshot`, `DayViewWidget.updateAll(Context)`.
- Produces: `AndroidDayPreferences.observe(observer): () -> Unit` backed by a process-wide `SharedPreferences.OnSharedPreferenceChangeListener` with snapshot dedup; the in-memory observer map is removed.

- [ ] **Step 1: Write the failing cross-instance test**

Append to `AndroidDayPreferencesTest.kt` (inside the class, after `observersReceiveSnapshotsUntilTheyUnsubscribe`):

```kotlin
    @Test
    fun observersSeeChangesMadeThroughAnotherInstance() {
        val observed = mutableListOf<DayPreferencesSnapshot>()
        val stopObserving = preferences.observe(observed::add)

        val otherInstance = AndroidDayPreferences(context, notifyWidgets = false)
        otherInstance.savePomodoro(50, 1_800_000_000_000L)
        otherInstance.saveFocusIntention("Depuis la tuile")

        assertEquals(50, observed.last().pomodoroMinutes)
        assertEquals(1_800_000_000_000L, observed.last().pomodoroEndMillis)
        assertEquals("Depuis la tuile", observed.last().focusIntention)

        stopObserving()
        val sizeAfterStop = observed.size
        otherInstance.saveShowSeconds(false)
        assertEquals(sizeAfterStop, observed.size)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.AndroidDayPreferencesTest"`
Expected: FAIL — the current in-memory-map `observe()` does not see writes made through `otherInstance`, so `observed.last()` still holds the initial snapshot (`pomodoroMinutes == 25`).

- [ ] **Step 3: Reimplement `observe()` on a SharedPreferences listener**

In `AndroidDayPreferences.kt`, add the import (with the other `android.content` imports):

```kotlin
import android.content.SharedPreferences
```

Replace the observer fields and helper — delete these lines:

```kotlin
    private val observers = mutableMapOf<Long, (DayPreferencesSnapshot) -> Unit>()
    private var nextObserverId = 0L

    private fun preferencesChanged(updateWidgets: Boolean = false) {
        val updated = snapshot()
        observers.values.toList().forEach { it(updated) }
        if (updateWidgets && notifyWidgets) DayViewWidget.updateAll(appContext)
    }

    override fun observe(observer: (DayPreferencesSnapshot) -> Unit): () -> Unit {
        val observerId = nextObserverId++
        observers[observerId] = observer
        observer(snapshot())
        return { observers.remove(observerId) }
    }
```

and replace them with:

```kotlin
    private fun refreshWidgets() {
        if (notifyWidgets) DayViewWidget.updateAll(appContext)
    }

    override fun observe(observer: (DayPreferencesSnapshot) -> Unit): () -> Unit {
        var last = snapshot()
        observer(last)
        // SharedPreferences notifies once per changed key; dedup so a multi-key
        // write (e.g. saveDayRange) yields a single snapshot per logical change.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val current = snapshot()
            if (current != last) {
                last = current
                observer(current)
            }
        }
        storage.registerOnSharedPreferenceChangeListener(listener)
        return { storage.unregisterOnSharedPreferenceChangeListener(listener) }
    }
```

Then update every save method: replace `preferencesChanged(updateWidgets = true)` with `refreshWidgets()`, and delete the bare `preferencesChanged()` calls (observer notification is now handled by the listener). Concretely:

- `saveDayRange`: replace `preferencesChanged(updateWidgets = true)` → `refreshWidgets()`.
- `saveShowSeconds`: delete the `preferencesChanged()` line.
- `saveSoundSettings`: delete the `preferencesChanged()` line.
- `saveGlobalGoal`: replace `preferencesChanged(updateWidgets = true)` → `refreshWidgets()`.
- `savePomodoro`: replace `preferencesChanged(updateWidgets = true)` → `refreshWidgets()`.
- `saveFocusIntention`: replace `preferencesChanged(updateWidgets = true)` → `refreshWidgets()`.

- [ ] **Step 4: Run the Android preferences tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.AndroidDayPreferencesTest"`
Expected: PASS — the new cross-instance test and the existing `observersReceiveSnapshotsUntilTheyUnsubscribe` (still exactly 3 emissions, thanks to dedup) both pass.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt \
        composeApp/src/androidUnitTest/kotlin/fr/dayview/app/AndroidDayPreferencesTest.kt
git commit -m "feat(android): propagate preference changes across components via SharedPreferences listener"
```

---

## Task 3: Remove the MainActivity recreate() hack

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt`

**Interfaces:**
- Consumes: `AndroidDayPreferences`, `FocusAlarmScheduler`, `DayViewWidget.updateAll(Context)`.
- Produces: no new API. Removes `displayedFocusEndMillis`, `recreateIfFocusChanged()`, and the `onWindowFocusChanged` override.

- [ ] **Step 1: Remove the recreate machinery**

In `MainActivity.kt`:

Delete the field:

```kotlin
    private var displayedFocusEndMillis: Long? = null
```

In `onCreate`, delete the line `displayedFocusEndMillis = preferences.loadPomodoroEndMillis()` and, inside the `onFocusAlarmChange` lambda, delete the line `displayedFocusEndMillis = endMillis`. The lambda becomes:

```kotlin
                onFocusAlarmChange = { endMillis, intention ->
                    if (endMillis == null) {
                        focusAlarmScheduler.cancel()
                    } else {
                        val scheduledExactly = focusAlarmScheduler.schedule(endMillis, intention)
                        requestRequiredAccess(requestExactAlarm = !scheduledExactly)
                    }
                },
```

Replace `onResume` with (dropping the recreate check, keeping widget refresh and alarm restore):

```kotlin
    override fun onResume() {
        super.onResume()
        DayViewWidget.updateAll(applicationContext)
        if (::focusAlarmScheduler.isInitialized) {
            restoreActiveFocusAlarm()
        }
    }
```

Delete the entire `onWindowFocusChanged` override:

```kotlin
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::preferences.isInitialized) recreateIfFocusChanged()
    }
```

Delete the entire `recreateIfFocusChanged` method:

```kotlin
    private fun recreateIfFocusChanged(): Boolean {
        if (preferences.loadPomodoroEndMillis() == displayedFocusEndMillis) return false
        recreate()
        return true
    }
```

- [ ] **Step 2: Verify no dangling references**

Run: `git grep -n "displayedFocusEndMillis\|recreateIfFocusChanged\|onWindowFocusChanged" composeApp/src`
Expected: no output (all references removed).

- [ ] **Step 3: Compile the Android app and run Android tests**

Run: `./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt
git commit -m "refactor(android): drop recreate() hack in favor of live preference updates"
```

---

## Task 4: Full green gate

**Files:** none (verification + formatting only).

- [ ] **Step 1: Apply ktlint formatting**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL (may reformat touched files).

- [ ] **Step 2: Run the full green gate**

Run: `./gradlew ktlintCheck testDebugUnitTest desktopTest assembleDebug assembleRelease`
Expected: BUILD SUCCESSFUL. Confirm the test summary shows 0 failures.

- [ ] **Step 3: Commit any formatting changes**

If `git status` shows changes from Step 1:

```bash
git add -A
git commit -m "style: apply ktlint formatting"
```

If there are no changes, skip this step.

- [ ] **Step 4: Push and open a PR**

```bash
git push -u origin claude/reactive-preferences-loop
gh pr create --base main --title "feat: reactive preferences loop (replace Android recreate() hack)" --body "Implements docs/superpowers/specs/2026-07-11-reactive-preferences-loop-design.md. Controller owns observable state and reconciles external writes; Android observe() propagates across components via a SharedPreferences listener; MainActivity's recreate() hack is removed."
```

---

## Self-Review

**Spec coverage:**
- Controller owns `mutableStateOf` state + reconciliation → Task 1. ✓
- Update-first/persist-second ordering → Task 1 setters. ✓
- Transient-field preservation (`nowMillis`, `goalDeadlineText`, `lastFocusClosure`, `destination`) → Task 1 `withPersisted` + `onPreferencesChangedPreservesTransientUiState` test. ✓
- App.kt wiring via `observe()`; remove state mirror/reassignments → Task 1 Step 5. ✓
- Android `observe()` via `OnSharedPreferenceChangeListener`; remove in-memory map; keep widget refresh → Task 2. ✓
- Cross-instance regression test → Task 2 Step 1. ✓
- Remove `recreate()` machinery → Task 3. ✓
- Desktop untouched → no task modifies `DesktopDayPreferences`/`Main.kt`. ✓
- Green gate → Task 4. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. ✓

**Type consistency:** `onPreferencesChanged(DayPreferencesSnapshot)`, `state: DayViewUiState`, setter names, and `refreshWidgets()`/`coerced()`/`withPersisted()` helper names are used identically across tasks. Setters return `Unit` consistently; the one test reading a return value is fixed in Task 1 Step 4. ✓
