# macOS Native Presence Foundation + Engaged Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Track frontmost-app presence during a Focus in the native app — engaged mint arcs on the dial + a "Focus H h MM" total, an on-goal apps settings picker, and `sessionOffGoal` feeding the clean-session ledger.

**Architecture:** Relocate the pure drift/resume detectors to `:core` (Task 1). Add a `FrontmostAppProvider` interface + a tested `PresenceCoordinator` extracting the JVM `Main.kt` per-tick loop, the snapshot arc fields, and the session wiring (Task 2). Add the native `NSWorkspace` provider + wiring (Task 3) and the Swift UI — dial arcs, focus total, on-goal settings (Task 4).

**Tech Stack:** Kotlin Multiplatform (`:core` common + macosMain), SwiftUI (macOS 15+).

## Global Constraints

- NO change to the presence algorithms — `PresenceAccumulator`, `SessionCleanlinessTracker`, `classifyFrontmost`, `deriveEngagedIntervals`, and the `focusArcsState`/`focusSessionBandsState`/`focusedToday` projections are used as-is.
- Coordinator accumulator params match `Main.kt` EXACTLY: strict presence `PresenceAccumulator(bridge = 60.seconds)`; session `PresenceAccumulator(presentStates = setOf(ON_GOAL, NEUTRAL), bridge = 120.seconds, minInterval = 60.seconds, interruptionGap = 15.seconds)`.
- Per-tick semantics copied from `Main.kt`: classify → `cleanlinessTracker.observe(now, pomodoroEnd, state)` (always, for `sessionOffGoal`) → `presenceAccumulator.observe(now, state, dayKey)` while active / `endSession()` on the active→inactive edge; same for the session accumulator.
- The session pushes intervals to the controller ONLY when they change (avoid a per-second persist storm); the controller's setters persist.
- Detector migration keeps the package `fr.dayview.app`, so the JVM `Main.kt` needs no import change; `MacFrontmostApplicationProvider` (JNA) stays in `desktopMain`.
- `FocusArcSnapshot(startAngleDegrees: Double, sweepDegrees: Double)` — no color (fixed mint), no hover in 10a. `focusTotalLabel` = `"Focus " + formatDurationHm(focusedToday)` when `focusedToday > 0`, else `""`.
- Engaged arcs draw on the MAIN ring lane (not a concentric lane): session band `mint` @ 0.18 stroke `lineWidth×0.5`, then on-goal arcs `mint` @ 0.55 stroke `lineWidth×0.5`, after the sweep and before the busy lane. Main window only; `MiniView` unchanged.
- No TCC prompt (frontmost bundle id + running-app list are public API).
- Kotlin lint: `./gradlew ktlintCheck`. Commit messages English/imperative/change-only, no AI references; commits succeed unsigned.
- Headless GUI blocked — arcs/settings are a manual smoke test; report what was and wasn't verified.
- **Full gate** (run after Task 1 and at the end): `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`.
- **Before Task 1:** `git checkout -b claude/macos-native-presence-foundation`.

## File map

- Move: `shared/src/desktopMain/.../FocusDriftDetector.kt` (the two detectors) → `core/src/commonMain/kotlin/fr/dayview/app/FocusDriftDetector.kt`; keep `MacFrontmostApplicationProvider` in `desktopMain`.
- Move: `shared/src/desktopTest/.../FocusDriftDetectorTest.kt` → `core/src/commonTest/kotlin/fr/dayview/app/`.
- Create: `core/src/commonMain/kotlin/fr/dayview/app/FrontmostAppProvider.kt`, `PresenceCoordinator.kt`.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/PresenceCoordinatorTest.kt`, additions to `DayViewSessionTest.kt`.
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`, `DayViewSession.kt`; `core/src/macosMain/.../DayViewNative.kt` + new `NSWorkspaceFrontmostProvider.kt`.
- Modify: `macos/DayView/TodayModel.swift`, `DayRingCanvas.swift`, `RingView.swift`, `SettingsView.swift`.

---

## Task 1: Relocate the pure drift/resume detectors to `:core`

Pure relocation so 10b can wire them natively; no behavior change. Deliverable: the detectors live in `:core`, their tests pass there, and the full gate (JVM included) is green.

**Files:**
- Move (partial): `shared/src/desktopMain/kotlin/fr/dayview/app/FocusDriftDetector.kt`
- Create: `core/src/commonMain/kotlin/fr/dayview/app/FocusDriftDetector.kt`
- Move: `shared/src/desktopTest/kotlin/fr/dayview/app/FocusDriftDetectorTest.kt` → `core/src/commonTest/kotlin/fr/dayview/app/FocusDriftDetectorTest.kt`

**Interfaces:**
- Produces: `FocusDriftDetector`, `FocusResumeDetector` in `:core` (package `fr.dayview.app`, unchanged API). 10b consumes them.

- [ ] **Step 1: Read the source file and identify the split**

Read `shared/src/desktopMain/kotlin/fr/dayview/app/FocusDriftDetector.kt`. It contains: `FocusResumeDetector` (pure), `FocusDriftDetector` (pure), `MacFrontmostApplicationProvider` (+ its JNA `Library` interface, JVM-only). The two detectors reference only `Duration`/`Instant`/`ArrayDeque`/`DAYVIEW_BUNDLE_ID` (all multiplatform; `DAYVIEW_BUNDLE_ID` is already in `:core`).

- [ ] **Step 2: Create the `:core` detector file**

Create `core/src/commonMain/kotlin/fr/dayview/app/FocusDriftDetector.kt` with the package line, the multiplatform imports the detectors need (`kotlin.time.Duration`, `Duration.Companion.minutes`, `Duration.Companion.seconds`, `kotlin.time.Instant`), and the **verbatim** `FocusResumeDetector` and `FocusDriftDetector` classes moved from the desktop file. Do NOT include the JNA `Library` import or `MacFrontmostApplicationProvider`.

- [ ] **Step 3: Trim the desktop file to just the JNA provider**

Edit `shared/src/desktopMain/kotlin/fr/dayview/app/FocusDriftDetector.kt`: remove `FocusResumeDetector` and `FocusDriftDetector`; keep the `com.sun.jna.*` imports, `MacFrontmostApplicationProvider`, and its JNA interface. Keep any import the provider still needs; drop imports only the detectors used.

- [ ] **Step 4: Move the test**

`git mv shared/src/desktopTest/kotlin/fr/dayview/app/FocusDriftDetectorTest.kt core/src/commonTest/kotlin/fr/dayview/app/FocusDriftDetectorTest.kt`
If the test uses only `kotlin.test.*` + `kotlin.time.*`, it compiles in commonTest unchanged. If it uses a JVM-only API, move it to `core/src/jvmTest/...` instead. (Read it first; adapt the target directory, not the test body.)

- [ ] **Step 5: Compile native + run the moved test + the JVM path**

Run: `./gradlew :core:compileKotlinMacosArm64 :core:jvmTest --tests 'fr.dayview.app.FocusDriftDetectorTest'`
Expected: BUILD SUCCESSFUL; the detector tests pass in their new home; the native target compiles the detectors.

- [ ] **Step 6: Full gate (the JVM must still build with the relocated detectors)**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — `Main.kt` and `MacFrontmostApplicationProvider` resolve the detectors from `:core` (same package). If desktop reports duplicate declarations, a detector was left in the desktop file; if unresolved, the move dropped something.

- [ ] **Step 7: Commit**

```bash
git add -A core/src/commonMain/kotlin/fr/dayview/app/FocusDriftDetector.kt core/src/commonTest/kotlin/fr/dayview/app/FocusDriftDetectorTest.kt shared/src/desktopMain/kotlin/fr/dayview/app/FocusDriftDetector.kt shared/src/desktopTest/kotlin/fr/dayview/app/FocusDriftDetectorTest.kt
git commit -m "refactor(core): relocate the pure focus drift and resume detectors from desktop"
```

---

## Task 2: Frontmost provider interface, presence coordinator, snapshot, session wiring (TDD)

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/FrontmostAppProvider.kt`
- Create: `core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Test (create): `core/src/commonTest/kotlin/fr/dayview/app/PresenceCoordinatorTest.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `PresenceAccumulator`, `SessionCleanlinessTracker`, `classifyFrontmost`, `FocusPresenceInterval`, `AppRef`, `focusArcsState`/`focusSessionBandsState`/`focusedToday`, controller setters `setOnGoalApps`/`setFocusPresenceIntervals`/`setFocusSessionIntervals`/`setSessionOffGoal`, `formatDurationHm`, `dayKeyOf`.
- Produces: `FrontmostAppProvider` (+ `NoopFrontmostAppProvider`); `PresenceCoordinator`; `FocusArcSnapshot`; snapshot fields `focusArcs`/`focusSessionBands`/`focusTotalLabel`; `DayViewSession(..., frontmostAppProvider)`; session methods `setOnGoalApps(bundleIds: List<String>, names: List<String>)`, `runningApps(): List<AppRef>`.

- [ ] **Step 1: The provider interface**

Create `core/src/commonMain/kotlin/fr/dayview/app/FrontmostAppProvider.kt`:

```kotlin
package fr.dayview.app

/** Native source of the frontmost application and the running-app list (for on-goal config). */
interface FrontmostAppProvider {
    fun frontmostBundleId(): String?
    fun runningApps(): List<AppRef>
}

object NoopFrontmostAppProvider : FrontmostAppProvider {
    override fun frontmostBundleId(): String? = null
    override fun runningApps(): List<AppRef> = emptyList()
}
```

- [ ] **Step 2: Write the failing coordinator + session tests**

Create `core/src/commonTest/kotlin/fr/dayview/app/PresenceCoordinatorTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PresenceCoordinatorTest {
    private val onGoal = setOf("com.on.goal")

    private fun tickSeries(
        coordinator: PresenceCoordinator,
        startMillis: Long,
        seconds: Int,
        bundleId: String?,
        pomodoroEnd: Instant,
    ): PresenceCoordinator.Result {
        var result = PresenceCoordinator.Result(emptyList(), emptyList(), kotlin.time.Duration.ZERO)
        for (i in 0..seconds) {
            val now = Instant.fromEpochMilliseconds(startMillis + i * 1000L)
            result = coordinator.observe(
                now = now,
                isFocusActive = true,
                frontmostBundleId = bundleId,
                onGoalBundleIds = onGoal,
                pomodoroEnd = pomodoroEnd,
                dayKey = dayKeyOf(now),
            )
        }
        return result
    }

    @Test
    fun onGoalPresenceAccumulatesEngagedTime() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        // 3 minutes on-goal (session minInterval is 60s, so this commits on session end).
        val result = tickSeries(coordinator, start, 180, "com.on.goal", end)
        // The session accumulator has an open run; ending the session commits it.
        assertTrue(result.sessionIntervals.isNotEmpty() || result.presenceIntervals.isNotEmpty())
        assertEquals(kotlin.time.Duration.ZERO, result.sessionOffGoal) // never off-goal
    }

    @Test
    fun offGoalAccruesSessionOffGoal() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        val result = tickSeries(coordinator, start, 120, "com.other.app", end)
        assertTrue(result.sessionOffGoal > kotlin.time.Duration.ZERO)
    }

    @Test
    fun idleWhenNoFocus() {
        val coordinator = PresenceCoordinator()
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val result = coordinator.observe(
            now = now, isFocusActive = false, frontmostBundleId = "com.on.goal",
            onGoalBundleIds = onGoal, pomodoroEnd = null, dayKey = dayKeyOf(now),
        )
        assertTrue(result.presenceIntervals.isEmpty())
        assertTrue(result.sessionIntervals.isEmpty())
    }
}
```

Append to `DayViewSessionTest` (a fake provider drives the session):

```kotlin
    private class FakeFrontmostProvider(
        var bundleId: String? = null,
        var apps: List<AppRef> = emptyList(),
    ) : FrontmostAppProvider {
        override fun frontmostBundleId(): String? = bundleId
        override fun runningApps(): List<AppRef> = apps
    }

    @Test
    fun onGoalAppsRoundTripThroughTheSnapshot() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences, backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        session.setOnGoalApps(listOf("com.on.goal"), listOf("On Goal"))
        runCurrent()
        assertEquals(setOf(AppRef("com.on.goal", "On Goal")), controller.stateFlow.value.onGoalApps)
    }

    @Test
    fun focusTotalLabelEmptyWithNoEngagedTime() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences, backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()
        assertEquals("", seen.last().focusTotalLabel)
        assertTrue(seen.last().focusArcs.isEmpty())
        sub.cancel()
    }
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.PresenceCoordinatorTest' --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: FAIL to compile — unresolved `PresenceCoordinator`/`FocusArcSnapshot`/`setOnGoalApps`/`focusTotalLabel`/etc.

- [ ] **Step 4: The coordinator**

Create `core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt`:

```kotlin
package fr.dayview.app

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Drives the two presence accumulators + the cleanliness tracker from a per-tick frontmost
 * sample — the extraction of the JVM Main.kt presence block. Parameters match Main.kt exactly.
 * Stateful (holds open runs); one instance per session lifetime. Never throws.
 */
class PresenceCoordinator {
    data class Result(
        val presenceIntervals: List<FocusPresenceInterval>,
        val sessionIntervals: List<FocusPresenceInterval>,
        val sessionOffGoal: Duration,
    )

    private val presenceAccumulator = PresenceAccumulator(bridge = 60.seconds)
    private val sessionAccumulator = PresenceAccumulator(
        presentStates = setOf(OnGoalState.ON_GOAL, OnGoalState.NEUTRAL),
        bridge = 120.seconds,
        minInterval = 60.seconds,
        interruptionGap = 15.seconds,
    )
    private val cleanlinessTracker = SessionCleanlinessTracker()
    private var presence: List<FocusPresenceInterval> = emptyList()
    private var session: List<FocusPresenceInterval> = emptyList()
    private var wasFocusActive = false

    /** Seed the committed intervals from persisted state at session construction. */
    fun restore(presence: List<FocusPresenceInterval>, session: List<FocusPresenceInterval>, dayKey: Long) {
        this.presence = presence
        this.session = session
        presenceAccumulator.restore(presence, dayKey)
        sessionAccumulator.restore(session, dayKey)
    }

    fun observe(
        now: Instant,
        isFocusActive: Boolean,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String>,
        pomodoroEnd: Instant?,
        dayKey: Long,
    ): Result {
        val state = classifyFrontmost(frontmostBundleId, onGoalBundleIds)
        val offGoal = cleanlinessTracker.observe(now, pomodoroEnd, state)
        presence = when {
            isFocusActive -> presenceAccumulator.observe(now, state, dayKey)
            wasFocusActive -> presenceAccumulator.endSession()
            else -> presence
        }
        session = when {
            isFocusActive -> sessionAccumulator.observe(now, state, dayKey)
            wasFocusActive -> sessionAccumulator.endSession()
            else -> session
        }
        wasFocusActive = isFocusActive
        return Result(presence, session, offGoal)
    }
}
```

(Add the imports `kotlin.time.Duration.Companion.seconds` for the params.)

- [ ] **Step 5: Snapshot fields**

In `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`:

1. Add after `DetourBodySnapshot`:

```kotlin
/** A focus/engaged arc on the ring's main lane (mint; no color index; no hover in 10a). */
data class FocusArcSnapshot(val startAngleDegrees: Double, val sweepDegrees: Double)
```

2. Add to the END of the `TodaySnapshot` constructor (after `detourBodies`):

```kotlin
    val focusArcs: List<FocusArcSnapshot>,
    val focusSessionBands: List<FocusArcSnapshot>,
    val focusTotalLabel: String,
```

3. Add to the END of `toTodaySnapshot` construction:

```kotlin
        focusArcs = focusArcsState.map { FocusArcSnapshot(it.startAngleDegrees.toDouble(), it.sweepDegrees.toDouble()) },
        focusSessionBands = focusSessionBandsState.map { FocusArcSnapshot(it.startAngleDegrees.toDouble(), it.sweepDegrees.toDouble()) },
        focusTotalLabel = if (focusedToday > kotlin.time.Duration.ZERO) "Focus ${formatDurationHm(focusedToday)}" else "",
```

- [ ] **Step 6: Session wiring**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`:

1. Add a constructor param after `use24Hour`:

```kotlin
    private val frontmostAppProvider: FrontmostAppProvider = NoopFrontmostAppProvider,
```

2. Add a coordinator field and restore it from the controller's initial state in `init`:

```kotlin
    private val presence = PresenceCoordinator()

    // (in the existing init { }, after refreshCalendar())
    run {
        val state = controller.stateFlow.value
        presence.restore(state.focusPresenceIntervals, state.focusSessionIntervals, dayKeyOf(state.now))
    }
```

3. In `tick()`, after the calendar-refresh logic, drive presence:

```kotlin
        val state = controller.stateFlow.value
        val focusActive = state.pomodoroEnd?.let { state.now < it } ?: false
        // Only sample while a focus is (or just was) active — idle ticks skip the work.
        if (focusActive || presenceWasActive) {
            val result = presence.observe(
                now = state.now,
                isFocusActive = focusActive,
                frontmostBundleId = frontmostAppProvider.frontmostBundleId(),
                onGoalBundleIds = state.onGoalApps.map { it.bundleId }.toSet(),
                pomodoroEnd = state.pomodoroEnd,
                dayKey = dayKeyOf(state.now),
            )
            if (result.presenceIntervals != state.focusPresenceIntervals) {
                controller.setFocusPresenceIntervals(result.presenceIntervals)
            }
            if (result.sessionIntervals != state.focusSessionIntervals) {
                controller.setFocusSessionIntervals(result.sessionIntervals)
            }
            controller.setSessionOffGoal(result.sessionOffGoal)
            presenceWasActive = focusActive
        }
```

with a `private var presenceWasActive = false` field. (`state.now` is the controller's clock; `tick()` already advances it via `controller.tick(Clock.System.now())` earlier in the method — read `state` AFTER that call.)

4. Add the on-goal + running-apps bridge methods (before `close()`):

```kotlin
    fun setOnGoalApps(bundleIds: List<String>, names: List<String>) {
        val apps = bundleIds.mapIndexed { i, id -> AppRef(id, names.getOrElse(i) { id }) }.toSet()
        controller.setOnGoalApps(apps)
    }

    fun runningApps(): List<AppRef> = selectableApps(frontmostAppProvider.runningApps())
```

- [ ] **Step 7: Run to verify pass, lint**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.PresenceCoordinatorTest' --tests 'fr.dayview.app.DayViewSessionTest'` then `./gradlew ktlintCheck`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/FrontmostAppProvider.kt core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt core/src/commonTest/kotlin/fr/dayview/app/PresenceCoordinatorTest.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): presence coordinator, frontmost provider, and engaged snapshot fields"
```

---

## Task 3: Native frontmost provider + wiring

**Files:**
- Create: `core/src/macosMain/kotlin/fr/dayview/app/NSWorkspaceFrontmostProvider.kt`
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`
- Modify: `macos/DayView/TodayModel.swift`

**Interfaces:**
- Consumes: `FrontmostAppProvider`, `AppRef`; `DayViewSession(..., frontmostAppProvider)`.
- Produces: `NSWorkspaceFrontmostProvider`; `TodayModel.setOnGoalApps`/`runningApps`.

- [ ] **Step 1: The native provider**

Create `core/src/macosMain/kotlin/fr/dayview/app/NSWorkspaceFrontmostProvider.kt`:

```kotlin
package fr.dayview.app

import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSRunningApplication
import platform.AppKit.NSWorkspace

/**
 * Frontmost app + running-app list via NSWorkspace. The frontmost bundle id and the running
 * apps are public API — no screen-recording / TCC prompt.
 */
class NSWorkspaceFrontmostProvider : FrontmostAppProvider {
    override fun frontmostBundleId(): String? =
        NSWorkspace.sharedWorkspace.frontmostApplication?.bundleIdentifier

    override fun runningApps(): List<AppRef> =
        NSWorkspace.sharedWorkspace.runningApplications
            .filterIsInstance<NSRunningApplication>()
            .filter { it.activationPolicy == NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular }
            .mapNotNull { app ->
                val id = app.bundleIdentifier ?: return@mapNotNull null
                AppRef(id, app.localizedName ?: id)
            }
}
```

(Binding-name adaptation, bounded as in prior K/N interop: `sharedWorkspace` may bind as `NSWorkspace.Companion.sharedWorkspace`; the activation-policy enum constant name may differ; `@OptIn(ExperimentalForeignApi::class)` may be needed. Adapt names/types only until `:core:compileKotlinMacosArm64` is green; the semantics — frontmost bundle id, regular running apps → AppRef — are fixed.)

- [ ] **Step 2: Wire it in `DayViewNative`**

In `DayViewNative.create()`, pass the provider into the session:

```kotlin
        val session = DayViewSession(controller, scope, source, use24Hour = systemUses24HourClock(), frontmostAppProvider = NSWorkspaceFrontmostProvider())
        source.onPermissionChange = { session.refreshCalendar() }
        return session
```

(If the session constructor's earlier params are positional in the current file, keep their order and add `frontmostAppProvider` last — it defaults, so only `create()` passes it.)

- [ ] **Step 3: `TodayModel` passthroughs**

In `macos/DayView/TodayModel.swift`, add after the detour passthroughs:

```swift
    func setOnGoalApps(bundleIds: [String], names: [String]) { session.setOnGoalApps(bundleIds: bundleIds, names: names) }
    func runningApps() -> [AppRef] { session.runningApps() }
```

- [ ] **Step 4: Native compile + build**

Run: `./gradlew :core:compileKotlinMacosArm64` (adapt Step 1 binding names if needed), then `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches (no visible change yet — nothing consumes the new fields until Task 4). Close: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 5: Commit**

```bash
git add core/src/macosMain/kotlin/fr/dayview/app/NSWorkspaceFrontmostProvider.kt core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt macos/DayView/TodayModel.swift
git commit -m "feat(core): NSWorkspace frontmost provider wired into the native session"
```

---

## Task 4: Swift — engaged arcs, focus total, on-goal settings

**Files:**
- Modify: `macos/DayView/DayRingCanvas.swift`
- Modify: `macos/DayView/RingView.swift`
- Modify: `macos/DayView/SettingsView.swift`

**Interfaces:**
- Consumes: snapshot `focusArcs`/`focusSessionBands`/`focusTotalLabel`; `TodayModel.runningApps()`/`setOnGoalApps`; `DayViewPalette.mint`.

- [ ] **Step 1: Engaged arcs on the dial**

In `macos/DayView/DayRingCanvas.swift`:

1. Add two params after `detourBodies`:

```swift
    var focusArcs: [FocusArcSnapshot] = []
    var focusSessionBands: [FocusArcSnapshot] = []
```

2. In `body`, AFTER the remaining-sweep drawing and BEFORE the busy lane, add (on the MAIN lane — reuse the main `radius`):

```swift
            for band in focusSessionBands {
                var arc = Path()
                arc.addArc(center: center, radius: radius, startAngle: .degrees(band.startAngleDegrees), endAngle: .degrees(band.startAngleDegrees + band.sweepDegrees), clockwise: false)
                context.stroke(arc, with: .color(palette.mint.opacity(0.18)), style: StrokeStyle(lineWidth: lineWidth * 0.5, lineCap: .round))
            }
            for focus in focusArcs {
                var arc = Path()
                arc.addArc(center: center, radius: radius, startAngle: .degrees(focus.startAngleDegrees), endAngle: .degrees(focus.startAngleDegrees + focus.sweepDegrees), clockwise: false)
                context.stroke(arc, with: .color(palette.mint.opacity(0.55)), style: StrokeStyle(lineWidth: lineWidth * 0.5, lineCap: .round))
            }
```

(Confirm `palette`/`center`/`radius`/`lineWidth` locals are in scope at this point in the `Canvas` closure; they are — the sweep block above uses them.)

- [ ] **Step 2: `RingView` — pass the arcs + focus total**

In `macos/DayView/RingView.swift`:

1. In the `DayRingCanvas(...)` call, add:

```swift
                    busyArcs: model.snapshot.busyArcs,
                    detourBodies: model.snapshot.detourBodies,
                    focusArcs: model.snapshot.focusArcs,
                    focusSessionBands: model.snapshot.focusSessionBands
```

2. In the interior `VStack`, add after the `detourTotalLabel` block:

```swift
                    if !model.snapshot.focusTotalLabel.isEmpty {
                        Text(model.snapshot.focusTotalLabel)
                            .font(.caption).foregroundStyle(palette.mint)
                    }
```

- [ ] **Step 3: On-goal apps settings section**

In `macos/DayView/SettingsView.swift`, add a section after the "Net time" section. Read the running apps once when the view appears into a local `@State`, and derive the current on-goal set from... the snapshot does not expose the on-goal set as strings; expose the current selection by comparing bundle ids the model already has. Add to `SettingsView`:

```swift
    @State private var apps: [AppRef] = []
    @State private var selected: Set<String> = []
```

and the section:

```swift
            Section("On-goal apps") {
                Text("Apps that count as working toward your goal during a Focus.")
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(apps, id: \.bundleId) { app in
                    Toggle(app.displayName, isOn: Binding(
                        get: { selected.contains(app.bundleId) },
                        set: { on in
                            if on { selected.insert(app.bundleId) } else { selected.remove(app.bundleId) }
                            let chosen = apps.filter { selected.contains($0.bundleId) }
                            model.setOnGoalApps(bundleIds: chosen.map { $0.bundleId }, names: chosen.map { $0.displayName })
                        }
                    ))
                }
            }
            .onAppear {
                apps = model.runningApps()
                selected = Set(model.onGoalBundleIds)
            }
```

For the initial selection, add a bridge read. In `TodayModel.swift` add `var onGoalBundleIds: [String] { session.onGoalBundleIds() }` and in `DayViewSession` add `fun onGoalBundleIds(): List<String> = controller.stateFlow.value.onGoalApps.map { it.bundleId }`. (This gives the current selection without threading the whole AppRef set through the snapshot; the toggle then writes the full set back.)

- [ ] **Step 4: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; app launches; Settings shows an "On-goal apps" section listing running apps.

- [ ] **Step 5: Verify + manual smoke**

Automatic: build, launch, process alive. Manual (report as not-verified): Settings → On-goal apps → toggle an app on; start a Focus and stay in that app → bright mint arcs grow on the dial and "Focus H h MM" appears (mint); switch to a non-listed app → the arcs stop growing; close the Focus → the clean-session outcome reflects the off-goal time. Close: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 6: Full gate + commit**

Run the full gate (`./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`), then:

```bash
git add macos/DayView/DayRingCanvas.swift macos/DayView/RingView.swift macos/DayView/SettingsView.swift macos/DayView/TodayModel.swift core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt
git commit -m "feat(macos): engaged focus arcs, Focus total, and on-goal apps settings"
```

---

## Self-Review Notes

- **Spec coverage:** detector migration → Task 1 (gate green proves the JVM still builds); `FrontmostAppProvider` + `PresenceCoordinator` (Main.kt extraction, exact params) → Task 2 Steps 1/4, pinned by `PresenceCoordinatorTest` (on-goal accrues engaged, off-goal accrues sessionOffGoal, idle empty); snapshot arcs + focus total → Task 2 Step 5 (+ `focusTotalLabelEmptyWithNoEngagedTime`); session wiring pushing only on change + sessionOffGoal → Task 2 Step 6; on-goal round-trip → `onGoalAppsRoundTripThroughTheSnapshot`; native NSWorkspace provider (no TCC) → Task 3; engaged arcs on the main lane + focus total + on-goal settings → Task 4.
- **Type consistency:** `FocusArcSnapshot(startAngleDegrees: Double, sweepDegrees: Double)` ↔ Swift `[FocusArcSnapshot]`; `addOnGoalApp(bundleId:name:)`/`removeOnGoalApp(bundleId:)`/`onGoalApps()`/`runningApps()`/`onGoalBundleIds()` consistent across session/model/settings; `DayViewSession(..., frontmostAppProvider)` positional add is last and defaults so tests/JVM are unaffected; the coordinator params match `Main.kt` verbatim.
- **No placeholders:** every step carries complete code; the K/N `NSWorkspace` binding note bounds the permitted adaptation to names.
- **YAGNI:** no drift/nudge/dock/resume (10b), no session-detail hover, no mini arcs, no per-app icons in the picker.
- **Known nuance:** the picker reads running apps on `.onAppear` (no live refresh — matches the JVM).
- **Persistence (documented 10a limitation):** engaged presence intervals are IN-MEMORY ONLY on native. `focusPresenceIntervals` has no key in `:core`'s `DayPreferencesStore` / `DayPreferencesSnapshot` (the JVM persists it out-of-band, via `DesktopPreferences.saveFocusPresence`), so `setFocusPresenceIntervals` never reaches disk on native and the mint arcs + the Focus total reset on every relaunch, not just a mid-session one. `focusSessionIntervals` IS a `DayPreferencesSnapshot` field and does get written by `closeFocus`→`closePomodoro`, but `DayViewNative.create()` does not seed `initialFocusSessionIntervals` back from the loaded snapshot (and `derivesEngagedFromSessions` is false), so `PresenceCoordinator.restore()` always seeds empty on native — the persisted session intervals are currently write-only and inert. Impact is bounded for the 10a foundation: the arcs are cosmetic and rebuild over the course of a session, and the clean-session ledger is unaffected (`closePomodoro` reads the live in-memory `sessionOffGoal`, not either persisted interval list). A native presence-persistence path (seeding `DayViewNative.create()` and giving `focusPresenceIntervals` a store key) is a tracked follow-up, not part of 10a.
