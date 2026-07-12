# macOS Native Reactive State Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Compose-free domain layer + `DayViewController` into `:core`, convert the controller to a `StateFlow`, and bridge it to SwiftUI so the native ring is driven by the real controller with a live start/stop-focus round-trip.

**Architecture:** The domain layer and controller relocate into the multi-target `:core` module; the controller's Compose `mutableStateOf` becomes a `StateFlow`-backed property (which lets it compile for Kotlin/Native). A hand-written `DayViewSession` collects that flow, maps `DayViewUiState` to a flattened `TodaySnapshot`, and hands it to a SwiftUI `ObservableObject`. Android and Linux keep using the same controller via `stateFlow.collectAsState()`.

**Tech Stack:** Kotlin Multiplatform 2.4.0, kotlinx-coroutines, kotlinx-datetime, Compose (Android/Linux only), Swift 5.9 / SwiftUI, XcodeGen, Xcode.

## Global Constraints

- `:core` must have **no Compose or androidx dependency** — this is the invariant that keeps it Kotlin/Native-compilable.
- Package stays `fr.dayview.app` for all moved files (no call-site import churn).
- Swift-exported names must not collide with Combine: the subscription protocol is `DayViewSubscription` (not `Cancellable`/`Subscription`).
- Persistence is deferred: the native session uses the existing in-memory `DefaultDayPreferences`; no new storage code.
- No Flow-bridging plugin (SKIE / KMP-NativeCoroutines) — hand-written bridge only.
- Commit messages: English, imperative, change-only. **Never** reference Claude/Anthropic/AI or add Co-Authored-By trailers.
- Gate after each task where noted: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Tasks 3–6 also run `./gradlew :core:compileKotlinMacosArm64`; Task 6 needs a macOS host with Xcode + `xcodegen`.

## Deviations from the spec (intentional)

- **Controller/state visibility:** `DayViewController`, `DayViewUiState`, `DayViewDestination`, `SettingsCategory` become `public` (not `internal`) because `:composeApp` consumes them across a module boundary. They therefore appear in the generated Swift header; our Swift code ignores them and uses only `DayViewSession`/`TodaySnapshot`. `@HiddenFromObjC` can curate the header in a later phase if the noise matters.
- **Test fixtures stay put:** `InMemoryDayPreferences` and `DayViewControllerTest` remain in `:composeApp` `commonTest` (desktop + android tests still use them; `:core` `commonTest` is not visible to `:composeApp`). The `:core` snapshot/observer tests seed state via `DefaultDayPreferences` instead.
- **Bridge consolidation:** the spec's separate `DayViewObserver` + `DayViewActions` are merged into one `DayViewSession` (subscribe + action methods) for a smaller Swift surface.
- **`TodaySnapshot` field set:** `goalStatus` is dropped for this phase (goal UI is deferred); the snapshot carries day-progress fields, pomodoro status/clock, `focusIntention`, and `dayStatus`.

---

## File Structure

- `core/build.gradle.kts` — add `kotlinx-coroutines-core` (commonMain) + `kotlinx-coroutines-test` (commonTest).
- `core/src/commonMain/kotlin/fr/dayview/app/` — receives the moved domain files, the split `CalendarModel.kt`, the moved `DayViewController.kt`, and new `TodaySnapshot.kt` + `DayViewNative.kt`.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarSource.kt` — shrinks to just `expect fun createCalendarSource()`.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` — switches the controller read to `collectAsState`.
- `macos/DayView/TodayModel.swift` — new `ObservableObject`.
- `macos/DayView/RingView.swift` — rewritten to read `TodayModel`.

---

## Task 1: Move the Compose-free domain layer into `:core`

Pure relocation (no behaviour change) of the domain files and their pure tests, plus splitting `CalendarSource.kt`. Deliverable: the domain compiles for macOS native and Android + Linux stay green off `:core`.

**Files:**
- Modify: `core/build.gradle.kts`
- Move (commonMain → `core`): `Pomodoro.kt`, `GlobalGoal.kt`, `CalendarNetTime.kt`, `PresenceAccumulator.kt`, `SoundAlerts.kt`, `OnGoalApps.kt`, `OnGoalState.kt`, `Detours.kt`, `ThemeMode.kt`, `DayPreferences.kt`
- Move (commonTest → `core`): `PomodoroTest.kt`, `GlobalGoalTest.kt`, `CalendarNetTimeTest.kt`, `DetoursTest.kt`, `FocusArcsTest.kt`, `PresenceAccumulatorTest.kt`, `SoundAlertsTest.kt`, `ThemeModeTest.kt`, `OnGoalAppsTest.kt`, `OnGoalClassificationTest.kt`, `DayPreferencesTest.kt`
- Create: `core/src/commonMain/kotlin/fr/dayview/app/CalendarModel.kt`
- Rewrite: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarSource.kt`

**Interfaces:**
- Produces (now in `:core`): `CalendarInfo`, `NetTimeSettings`, `CalendarSource` (interface), `NoopCalendarSource`, `nextIncludedCalendars`, plus all domain types/functions (`PomodoroProgress`, `PomodoroStatus`, `FocusClosureOutcome`, `focusIntentionAfterClosure`, `calculatePomodoroProgress`, `formatPomodoroClock`, `formatBreakClock`, `formatPomodoroCompactMinutes`, `BusyInterval`, `NetTime`, `BusyArc`, `FocusArc`, `dayWindow`, `calculateNetTime`, `busyArcs`, `focusArcs`, `focusedTime`, `DetourEpisode`, `DetourSource`, `DetourBody`, `detourBodies`, `detourSources`, `detoursTotal`, `sanitizeDetourMotif`, `pushRecentDetourMotif`, `dayKeyOf`, `MAX_RECENT_DETOUR_MOTIFS`, `AppRef`, `FocusPresenceInterval`, `SoundSettings`, `ThemeMode`, `DayPreferences`, `DayPreferencesSnapshot`, `DefaultDayPreferences`).
- Consumes: `:composeApp`'s `expect fun createCalendarSource()` and its desktop/android actuals now reference the `:core` `CalendarSource` interface (composeApp already depends on `:core`).

- [ ] **Step 1: Add coroutines dependencies to `:core`**

In `core/build.gradle.kts`, update the `commonMain` and `commonTest` dependency blocks:

```kotlin
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
```

- [ ] **Step 2: Move the domain source files into `:core`**

Run:

```bash
cd /Users/mremond/AIProjects/DayView/.claude/worktrees/dayview-zoom-integration-b405aa
for f in Pomodoro GlobalGoal CalendarNetTime PresenceAccumulator SoundAlerts OnGoalApps OnGoalState Detours ThemeMode DayPreferences; do
  git mv "composeApp/src/commonMain/kotlin/fr/dayview/app/$f.kt" "core/src/commonMain/kotlin/fr/dayview/app/$f.kt"
done
```

Do not edit contents — packages stay `fr.dayview.app`.

- [ ] **Step 3: Split `CalendarSource.kt` — pure part into `:core`**

Create `core/src/commonMain/kotlin/fr/dayview/app/CalendarModel.kt`:

```kotlin
package fr.dayview.app

import kotlin.time.Instant

data class CalendarInfo(val id: String, val displayName: String)

data class NetTimeSettings(
    val enabled: Boolean = false,
    val includedCalendarIds: Set<String> = emptySet(),
)

interface CalendarSource {
    fun isSupported(): Boolean
    fun hasPermission(): Boolean
    fun requestPermission()
    fun availableCalendars(): List<CalendarInfo>
    fun busyIntervals(
        windowStart: Instant,
        windowEnd: Instant,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval>
}

object NoopCalendarSource : CalendarSource {
    override fun isSupported() = false
    override fun hasPermission() = false
    override fun requestPermission() = Unit
    override fun availableCalendars(): List<CalendarInfo> = emptyList()
    override fun busyIntervals(
        windowStart: Instant,
        windowEnd: Instant,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> = emptyList()
}

/**
 * Calcule le prochain ensemble de calendriers inclus après une bascule.
 * Un ensemble vide signifie « tous inclus » ; l'inclusion de tous les calendriers
 * est renormalisée vers l'ensemble vide.
 */
fun nextIncludedCalendars(
    allIds: List<String>,
    current: Set<String>,
    toggledId: String,
    include: Boolean,
): Set<String> {
    val effective = if (current.isEmpty()) allIds.toSet() else current
    val updated = if (include) effective + toggledId else effective - toggledId
    return if (updated == allIds.toSet()) emptySet() else updated
}
```

- [ ] **Step 4: Shrink the `:composeApp` `CalendarSource.kt` to the platform hook**

Replace the entire contents of `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarSource.kt` with:

```kotlin
package fr.dayview.app

/** Platform factory for the calendar reader; actuals live in android/desktop. */
expect fun createCalendarSource(): CalendarSource
```

The existing `CalendarSource.android.kt` / `CalendarSource.desktop.kt` actuals are unchanged; they now implement the `:core` `CalendarSource` interface.

- [ ] **Step 5: Move the pure domain tests into `:core`**

Run:

```bash
for f in PomodoroTest GlobalGoalTest CalendarNetTimeTest DetoursTest FocusArcsTest PresenceAccumulatorTest SoundAlertsTest ThemeModeTest OnGoalAppsTest OnGoalClassificationTest DayPreferencesTest; do
  git mv "composeApp/src/commonTest/kotlin/fr/dayview/app/$f.kt" "core/src/commonTest/kotlin/fr/dayview/app/$f.kt"
done
```

`InMemoryDayPreferences.kt` and `DayViewControllerTest.kt` stay in `:composeApp` (still used by desktop/android tests).

- [ ] **Step 6: Verify `:core` compiles for native and its tests pass**

Run: `./gradlew :core:compileKotlinMacosArm64 :core:jvmTest`
Expected: BUILD SUCCESSFUL — the domain (incl. `DayPreferences`, coroutines) compiles for Kotlin/Native, and the moved tests pass from `:core`.

- [ ] **Step 7: Verify Android + Linux + lint stay green**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL — `:composeApp` (controller still here) consumes the moved domain from `:core`.

- [ ] **Step 8: Commit**

```bash
git add core composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarSource.kt composeApp/src/commonTest
git commit -m "refactor: move the Compose-free domain layer into :core"
```

---

## Task 2: Convert `DayViewController` to a `StateFlow`

Replace the Compose `mutableStateOf` property with a `StateFlow`-backed property (removing the controller's last Compose dependency) and adapt the one reactive read in `App.kt`. Deliverable: the Compose app runs off the `StateFlow` with all tests green.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`

**Interfaces:**
- Produces: `DayViewController.stateFlow: StateFlow<DayViewUiState>`; the existing `var state: DayViewUiState` (now getter/private-setter over the flow) is preserved for imperative reads.

- [ ] **Step 1: Swap the Compose runtime imports for coroutines flow**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`, replace:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
```

with:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

- [ ] **Step 2: Replace the state property with a flow-backed one**

In the same file, replace:

```kotlin
    var state: DayViewUiState by mutableStateOf(initialSnapshot.toUiState(initialNow))
        private set
```

with:

```kotlin
    private val _stateFlow = MutableStateFlow(initialSnapshot.toUiState(initialNow))
    val stateFlow: StateFlow<DayViewUiState> = _stateFlow.asStateFlow()
    var state: DayViewUiState
        get() = _stateFlow.value
        private set(value) {
            _stateFlow.value = value
        }
```

Every existing `state = state.copy(...)` and `state` read elsewhere in the class is unchanged — the custom getter/setter routes them through the flow.

- [ ] **Step 3: Make the App's controller read reactive**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`, add the import (near the other `androidx.compose.runtime` imports):

```kotlin
import androidx.compose.runtime.collectAsState
```

Then replace line `val state = controller.state` with:

```kotlin
            val state by controller.stateFlow.collectAsState()
```

(`App.kt` already imports `getValue` for its other `by` delegates.)

- [ ] **Step 4: Verify the Compose app compiles and all tests pass**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL — `DayViewControllerTest`, `FocusFlowTest`, and the UI tests pass against the `StateFlow`-backed controller; the today screen recomposes via `collectAsState`.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "refactor: back DayViewController state with a StateFlow"
```

---

## Task 3: Move `DayViewController` into `:core`

Now that the controller is Compose-free, relocate it and promote its declarations to `public`. Deliverable: the controller compiles for Kotlin/Native and the Compose app consumes it across the module boundary.

**Files:**
- Modify then move: `DayViewController.kt` (commonMain → `core`)

**Interfaces:**
- Produces (public, in `:core`): `DayViewController`, `DayViewUiState`, `DayViewDestination`, `SettingsCategory` and all existing controller methods (`tick`, `setFocusIntention`, `startPomodoro`, `stopPomodoro`, `changePomodoroDuration`, `openSettings`, …), plus `stateFlow`.

- [ ] **Step 1: Promote the four `internal` declarations to public**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`, remove the `internal` modifier from these four declarations (leave everything else, including the `private` helper functions, unchanged):

```kotlin
enum class DayViewDestination {
```
```kotlin
enum class SettingsCategory {
```
```kotlin
data class DayViewUiState(
```
```kotlin
class DayViewController(
```

- [ ] **Step 2: Move the file into `:core`**

Run:

```bash
git mv composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
       core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt
```

- [ ] **Step 3: Verify native compile + full gate**

Run: `./gradlew :core:compileKotlinMacosArm64 ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL — the controller compiles for macOS native; `:composeApp` (App.kt, settings screens, `DayViewControllerTest`) uses the now-public controller from `:core`.

- [ ] **Step 4: Commit**

```bash
git add composeApp core
git commit -m "refactor: move DayViewController into :core"
```

---

## Task 4: Add the `TodaySnapshot` mapping

Flatten `DayViewUiState` into the Swift-facing `TodaySnapshot`. Deliverable: a tested mapping in `:core`.

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Create: `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState`, `DayProgress`, `currentMomentAngleDegrees`, `PomodoroStatus`, `formatPomodoroClock`, `formatBreakClock`.
- Produces: `data class TodaySnapshot(...)` and `internal fun DayViewUiState.toTodaySnapshot(): TodaySnapshot`.

- [ ] **Step 1: Write the failing mapping test**

Create `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt`:

```kotlin
package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TodaySnapshotTest {
    private fun controllerWith(snapshot: DayPreferencesSnapshot, nowMillis: Long) =
        DayViewController(
            DefaultDayPreferences,
            CoroutineScope(Dispatchers.Unconfined),
            initialSnapshot = snapshot,
            initialNow = Instant.fromEpochMilliseconds(nowMillis),
        )

    @Test
    fun mapsIdleDayProgress() {
        val now = 1_700_000_000_000L
        val state = controllerWith(
            DayPreferencesSnapshot(startMinutes = 540, endMinutes = 1080),
            now,
        ).stateFlow.value

        val snap = state.toTodaySnapshot()

        assertEquals(state.dayProgress.remaining.inWholeSeconds, snap.remainingSeconds)
        assertEquals(state.dayProgress.isFinished, snap.isFinished)
        assertEquals(
            currentMomentAngleDegrees(state.dayProgress.remainingRatio).toDouble(),
            snap.momentAngleDegrees,
        )
        assertEquals("IDLE", snap.pomodoroStatus)
        assertEquals("", snap.pomodoroClock)
    }

    @Test
    fun mapsActiveFocus() {
        val now = 1_700_000_000_000L
        val state = controllerWith(
            DayPreferencesSnapshot(
                startMinutes = 540,
                endMinutes = 1080,
                pomodoroMinutes = 25,
                pomodoroEnd = Instant.fromEpochMilliseconds(now) + 25.minutes,
                focusIntention = "Ship it",
            ),
            now,
        ).stateFlow.value

        val snap = state.toTodaySnapshot()

        assertEquals("ACTIVE", snap.pomodoroStatus)
        assertEquals("Ship it", snap.focusIntention)
        assertEquals(formatPomodoroClock(state.pomodoroProgress), snap.pomodoroClock)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.TodaySnapshotTest"`
Expected: FAIL — `TodaySnapshot` / `toTodaySnapshot` unresolved.

- [ ] **Step 3: Implement the snapshot + mapping**

Create `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`:

```kotlin
package fr.dayview.app

/** Primitives-only view of the today screen for native (Swift) callers. */
data class TodaySnapshot(
    val remainingSeconds: Long,
    val remainingRatio: Double,
    val momentAngleDegrees: Double,
    val isFinished: Boolean,
    val remainingHours: Long,
    val remainingMinutes: Long,
    val pomodoroStatus: String, // "IDLE" | "ACTIVE" | "BREAK"
    val pomodoroClock: String, // formatted clock, "" when idle
    val focusIntention: String,
    val dayStatus: String, // remaining-time headline or "Day over"
)

internal fun DayViewUiState.toTodaySnapshot(): TodaySnapshot {
    val progress = dayProgress
    val pomodoro = pomodoroProgress
    return TodaySnapshot(
        remainingSeconds = progress.remaining.inWholeSeconds,
        remainingRatio = progress.remainingRatio.toDouble(),
        momentAngleDegrees = currentMomentAngleDegrees(progress.remainingRatio).toDouble(),
        isFinished = progress.isFinished,
        remainingHours = progress.remainingHours,
        remainingMinutes = progress.remainingMinutes,
        pomodoroStatus = pomodoro.status.name,
        pomodoroClock = when (pomodoro.status) {
            PomodoroStatus.ACTIVE -> formatPomodoroClock(pomodoro)
            PomodoroStatus.BREAK -> formatBreakClock(pomodoro)
            PomodoroStatus.IDLE -> ""
        },
        focusIntention = focusIntention,
        dayStatus = if (progress.isFinished) {
            "Day over"
        } else {
            "${progress.remainingHours}h ${progress.remainingMinutes.toString().padStart(2, '0')}m"
        },
    )
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.TodaySnapshotTest"`
Expected: PASS.

- [ ] **Step 5: Verify native compile + lint**

Run: `./gradlew :core:compileKotlinMacosArm64 ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt
git commit -m "feat(core): map DayViewUiState to a Swift-facing TodaySnapshot"
```

---

## Task 5: Add the `DayViewSession` observation bridge

A hand-written, dependency-free bridge that Swift subscribes to and drives. Deliverable: a tested `DayViewSession` that emits snapshots and reacts to actions, plus a `DayViewNative` entry point.

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/DayViewNative.kt`
- Create: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `DayViewController`, `DefaultDayPreferences`, `DayPreferencesSnapshot`, `TodaySnapshot`, `toTodaySnapshot`.
- Produces: `interface DayViewSubscription { fun cancel() }`; `class DayViewSession` with `currentSnapshot()`, `subscribe(onEach)`, `tick()`, `startFocus(intention)`, `stopFocus()`, `changePomodoroDuration(delta)`, `close()`; `object DayViewNative { fun create(): DayViewSession }`.

- [ ] **Step 1: Write the failing bridge test**

Create `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`:

```kotlin
package fr.dayview.app

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DayViewSessionTest {
    @Test
    fun emitsInitialThenReactsToStartFocus() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 540, endMinutes = 1080),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()

        val subscription = session.subscribe { seen.add(it) }
        runCurrent()
        assertEquals("IDLE", seen.last().pomodoroStatus)

        session.startFocus("Ship it")
        runCurrent()
        assertEquals("ACTIVE", seen.last().pomodoroStatus)
        assertEquals("Ship it", seen.last().focusIntention)

        subscription.cancel()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewSessionTest"`
Expected: FAIL — `DayViewSession` unresolved.

- [ ] **Step 3: Implement the bridge**

Create `core/src/commonMain/kotlin/fr/dayview/app/DayViewNative.kt`:

```kotlin
package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Handle returned by [DayViewSession.subscribe]; named to avoid Combine's `Cancellable`. */
interface DayViewSubscription {
    fun cancel()
}

/**
 * Native-facing wrapper over [DayViewController]: emits [TodaySnapshot]s and forwards actions.
 * All emissions run on the scope's dispatcher; [DayViewNative.create] uses the main dispatcher.
 */
class DayViewSession internal constructor(
    private val controller: DayViewController,
    private val scope: CoroutineScope,
) {
    fun currentSnapshot(): TodaySnapshot = controller.stateFlow.value.toTodaySnapshot()

    fun subscribe(onEach: (TodaySnapshot) -> Unit): DayViewSubscription {
        val job = scope.launch {
            controller.stateFlow.collect { onEach(it.toTodaySnapshot()) }
        }
        return object : DayViewSubscription {
            override fun cancel() = job.cancel()
        }
    }

    fun tick() = controller.tick(Clock.System.now())

    fun startFocus(intention: String) {
        controller.setFocusIntention(intention)
        controller.startPomodoro()
    }

    fun stopFocus() = controller.stopPomodoro()

    fun changePomodoroDuration(deltaMinutes: Int) = controller.changePomodoroDuration(deltaMinutes)

    fun close() = scope.cancel()
}

/** Single entry point Swift calls to build the whole graph with in-memory preferences. */
object DayViewNative {
    fun create(): DayViewSession {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val controller = DayViewController(
            DefaultDayPreferences,
            scope,
            initialSnapshot = DayPreferencesSnapshot(),
        )
        return DayViewSession(controller, scope)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewSessionTest"`
Expected: PASS.

- [ ] **Step 5: Verify native compile + full gate**

Run: `./gradlew :core:compileKotlinMacosArm64 ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewNative.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): add DayViewSession bridge for native observation"
```

---

## Task 6: Drive the native ring from the controller

Wire the SwiftUI app to the bridge: the ring is fed by observed snapshots, and start/stop-focus buttons prove the reactive round-trip. Deliverable: a launchable `.app` whose ring is controller-driven and whose focus label changes live on button press.

**Files:**
- Create: `macos/DayView/TodayModel.swift`
- Rewrite: `macos/DayView/RingView.swift`

**Interfaces:**
- Consumes: `DayViewNative.shared.create()`, `DayViewSession` (`currentSnapshot`, `subscribe`, `tick`, `startFocus`, `stopFocus`, `close`), `DayViewSubscription`, `TodaySnapshot`.

- [ ] **Step 1: Rebuild and publish the XCFramework with the new types**

Run: `./gradlew :core:syncXCFramework`
Expected: BUILD SUCCESSFUL; `macos/Packages/DayViewKit/DayViewKit.xcframework` refreshed with `TodaySnapshot` / `DayViewNative`.

- [ ] **Step 2: Create the observable model**

Create `macos/DayView/TodayModel.swift`:

```swift
import SwiftUI
import DayViewKit

final class TodayModel: ObservableObject {
    @Published var snapshot: TodaySnapshot
    private let session = DayViewNative.shared.create()
    private var subscription: DayViewSubscription?
    private var timer: Timer?

    init() {
        snapshot = session.currentSnapshot()
        subscription = session.subscribe { [weak self] snap in
            self?.snapshot = snap
        }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.session.tick()
        }
    }

    func startFocus() { session.startFocus(intention: "Ship it") }
    func stopFocus() { session.stopFocus() }

    deinit {
        subscription?.cancel()
        timer?.invalidate()
        session.close()
    }
}
```

- [ ] **Step 3: Rewrite the ring view to read the model**

Replace the entire contents of `macos/DayView/RingView.swift` with:

```swift
import SwiftUI
import DayViewKit

struct RingView: View {
    @StateObject private var model = TodayModel()

    var body: some View {
        VStack(spacing: 24) {
            Canvas { context, size in
                let inset: CGFloat = 40
                let side = min(size.width, size.height) - inset * 2
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = side / 2
                let lineWidth: CGFloat = 18

                var track = Path()
                track.addArc(
                    center: center, radius: radius,
                    startAngle: .degrees(0), endAngle: .degrees(360),
                    clockwise: false
                )
                context.stroke(track, with: .color(.gray.opacity(0.2)), lineWidth: lineWidth)

                var sweep = Path()
                sweep.addArc(
                    center: center, radius: radius,
                    startAngle: .degrees(-90),
                    endAngle: .degrees(model.snapshot.momentAngleDegrees),
                    clockwise: false
                )
                context.stroke(
                    sweep,
                    with: .color(.accentColor),
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            Text(model.snapshot.dayStatus)
                .font(.system(size: 44, weight: .semibold, design: .rounded))
                .monospacedDigit()

            Text(focusText)
                .foregroundStyle(.secondary)

            HStack(spacing: 16) {
                Button("Start focus") { model.startFocus() }
                Button("Stop focus") { model.stopFocus() }
            }
        }
        .padding(32)
    }

    private var focusText: String {
        let s = model.snapshot
        switch s.pomodoroStatus {
        case "ACTIVE": return "Focus · \(s.focusIntention) · \(s.pomodoroClock)"
        case "BREAK": return "Break · \(s.pomodoroClock)"
        default: return "Idle"
        }
    }
}
```

- [ ] **Step 4: Generate and build the app**

Run:

```bash
cd macos && xcodegen generate && cd -
xcodebuild -project macos/DayView.xcodeproj -scheme DayView -configuration Debug -derivedDataPath macos/build build
```

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 5: Launch and verify the reactive round-trip**

Run: `open macos/build/Build/Products/Debug/DayView.app`
Expected: the ring is controller-driven and ticks each second (label shows `Xh YYm`, "Idle" below). Clicking **Start focus** flips the label to `Focus · Ship it · 25:00` and it counts down live; **Stop focus** returns it to "Idle" — proving state flows Swift → Kotlin action → `StateFlow` → mapped snapshot → SwiftUI.

- [ ] **Step 6: Commit**

```bash
git add macos/DayView/TodayModel.swift macos/DayView/RingView.swift
git commit -m "feat(macos): drive the native ring from the shared controller"
```

---

## Self-Review Notes

- **Spec coverage:** domain+controller move → Tasks 1–3; `mutableStateOf`→`StateFlow` → Task 2; `TodaySnapshot` + mapping → Task 4; hand-written observation bridge + actions + native entry point → Task 5; SwiftUI `ObservableObject` wiring + live round-trip → Task 6. Done criteria (moved tests green from `:core`, mapping test, gate green on `collectAsState`, xcframework + native app with start/stop-focus round-trip) all map to explicit verification steps.
- **Deviations** are listed in the header block (visibility, test-fixture placement, bridge consolidation, dropped `goalStatus`) — each with rationale.
- **Type consistency:** `TodaySnapshot` field names/types are identical across Tasks 4, 5, 6; `DayViewSession` method names (`currentSnapshot`, `subscribe`, `tick`, `startFocus`, `stopFocus`, `changePomodoroDuration`, `close`) and `DayViewSubscription.cancel()` are consistent between the Kotlin definition (Task 5) and the Swift calls (Task 6); `DayViewNative.shared.create()` matches the Kotlin `object` → Swift `.shared` convention proven in the skeleton.
- **Placeholder scan:** no TBD/TODO; every code step shows complete content.
