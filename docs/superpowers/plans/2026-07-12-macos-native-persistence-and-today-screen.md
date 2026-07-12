# macOS Native Persistence + Today-Screen Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the native macOS app real persistence and native goal + focus/pomodoro editing, so edits survive relaunch.

**Architecture:** Move the shared `DayPreferencesStore` into `:core` (multiplatform DataStore) and add a `macosMain` file-backed factory; extend the `TodaySnapshot`/`DayViewSession` boundary with goal + focus fields/actions and one `Instant`-based controller setter; grow the SwiftUI `RingView` with native `TextField`/`DatePicker`/`Stepper` sections.

**Tech Stack:** Kotlin Multiplatform 2.4.0, androidx datastore-preferences-core 1.2.1 (multiplatform), okio, kotlinx-coroutines/datetime, Swift 5.9 / SwiftUI, XcodeGen, Xcode.

## Global Constraints

- `:core` must compile for Kotlin/Native and depend on **no Compose and no Android-only libraries**. Multiplatform, Native-compatible androidx (datastore-preferences-core) IS allowed.
- Package stays `fr.dayview.app`.
- Swift touches only the `DayViewNative`/`DayViewSession`/`TodaySnapshot`/`DayViewSubscription` surface; snapshot fields stay primitives/`String`.
- Persistence file path: `~/Library/Application Support/DayView/dayview.preferences_pb`.
- The Compose (Android/Linux) UI and its text-based goal setters must stay behavior-identical — this phase does not change them.
- Commit messages: English, imperative, change-only; no Claude/Anthropic/AI references, no Co-Authored-By.
- Gate where noted: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`. Native checks: `:core:compileKotlinMacosArm64` and, for persistence, `:core:macosArm64Test`.
- Tasks 2 & 5 need a macOS host with Xcode + `xcodegen`.

## Deviation note

The spec's "`:core` has no androidx" invariant is relaxed to "no Compose / no Android-only libs" — `datastore-preferences-core` is multiplatform and compiles for Native, so the binding rule (`:core` compiles for Kotlin/Native) holds.

---

## Task 1: Move `DayPreferencesStore` into `:core` (multiplatform DataStore)

Relocate the shared store + its round-trip test and add the datastore dependency. Deliverable: the store compiles for macOS native and its round-trip test passes on JVM **and** native — proving DataStore works on Kotlin/Native (the phase's primary risk), while Android/desktop keep using it.

**Files:**
- Modify: `core/build.gradle.kts`
- Move: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt` → `core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`
- Move: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt` → `core/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`

**Interfaces:**
- Produces (in `:core`): `class DayPreferencesStore(dataStore: DataStore<Preferences>) : DayPreferences` and `internal object DayPreferenceKeys`.

- [ ] **Step 1: Add datastore + okio-fakefilesystem to `:core`**

In `core/build.gradle.kts`, update the dependency blocks:

```kotlin
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.androidx.datastore.preferences.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.okio.fakefilesystem)
            }
        }
```

- [ ] **Step 2: Move the store and its test into `:core`**

```bash
cd /Users/mremond/AIProjects/DayView/.claude/worktrees/dayview-zoom-integration-b405aa
git mv composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt \
       core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt
git mv composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt \
       core/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt
```

Do not edit contents.

- [ ] **Step 3: Verify native compile + native round-trip test**

Run: `./gradlew :core:compileKotlinMacosArm64 :core:macosArm64Test :core:jvmTest`
Expected: BUILD SUCCESSFUL — `DayPreferencesStore` compiles for Kotlin/Native, and `DayPreferencesStoreTest` (real `OkioStorage` + `FakeFileSystem` round-trip) passes on both native and JVM.

If `datastore-preferences-core` 1.2.1 has no macOS-native artifact (native compile fails to resolve it), STOP and report BLOCKED — the fallback is a native `NSUserDefaults`-backed `DayPreferences` (spec option b), a plan change.

- [ ] **Step 4: Verify Android + desktop + lint stay green**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL — `DesktopPreferences` and Android consume `DayPreferencesStore` from `:core`.

- [ ] **Step 5: Commit**

```bash
git add core composeApp/build.gradle.kts core/build.gradle.kts
git commit -m "build: move DayPreferencesStore into :core with multiplatform DataStore"
```

---

## Task 2: Native DataStore factory + native entry point

Add the macOS persistence factory and route `DayViewNative` through it. Deliverable: `:core` compiles a native factory writing to Application Support, and the native app persists via it.

**Files:**
- Create: `core/src/macosMain/kotlin/fr/dayview/app/MacosDayPreferences.kt`
- Move + edit: `core/src/commonMain/kotlin/fr/dayview/app/DayViewNative.kt` → `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`

**Interfaces:**
- Consumes: `DayPreferencesStore` (Task 1), `DayViewController`, `DayViewSession` (stays in commonMain).
- Produces: `fun macosDayPreferences(): DayPreferences`; `object DayViewNative { fun create(): DayViewSession }` now in `macosMain`.

- [ ] **Step 1: Create the native factory**

Create `core/src/macosMain/kotlin/fr/dayview/app/MacosDayPreferences.kt`:

```kotlin
package fr.dayview.app

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

/**
 * File-backed [DayPreferences] for the native macOS app, stored under
 * ~/Library/Application Support/DayView. Reuses the shared [DayPreferencesStore] encoding.
 */
fun macosDayPreferences(): DayPreferences {
    val dir = "${NSHomeDirectory()}/Library/Application Support/DayView".toPath()
    FileSystem.SYSTEM.createDirectories(dir)
    val path = dir / "dayview.preferences_pb"
    val storage = OkioStorage(
        fileSystem = FileSystem.SYSTEM,
        serializer = PreferencesSerializer,
        producePath = { path },
    )
    return DayPreferencesStore(PreferenceDataStoreFactory.create(storage = storage))
}
```

If `okio.FileSystem` is unresolved, add `implementation(libs.okio)` to `:core` commonMain (datastore brings okio transitively, but an explicit dep may be needed) — check the version catalog for the alias; if absent, add `okio = "<version datastore resolves>"` and `okio = { module = "com.squareup.okio:okio", version.ref = "okio" }`, then report it as a concern.

- [ ] **Step 2: Move `DayViewNative` into `macosMain` and use the native factory**

```bash
git mv core/src/commonMain/kotlin/fr/dayview/app/DayViewNative.kt \
       core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt
```

Then in the moved file, change the `create()` body to use `macosDayPreferences()`:

```kotlin
object DayViewNative {
    fun create(): DayViewSession {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val preferences = macosDayPreferences()
        val controller = DayViewController(
            preferences,
            scope,
            initialSnapshot = runBlocking { preferences.snapshots.first() },
        )
        return DayViewSession(controller, scope)
    }
}
```

Add imports: `kotlinx.coroutines.flow.first`, `kotlinx.coroutines.runBlocking`. Keep `DayViewSubscription`, `DayViewSession` in their current `commonMain` file (`DayViewNative.kt` in commonMain currently holds all three — move ONLY the `object DayViewNative` to macosMain; leave `interface DayViewSubscription` and `class DayViewSession` in a commonMain file, e.g. rename the commonMain file's remaining content into `DayViewSession.kt`).

Concretely: split the old `DayViewNative.kt` — `DayViewSubscription` + `DayViewSession` go to `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`; `object DayViewNative` goes to `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt` with the factory change above.

- [ ] **Step 3: Verify native compile**

Run: `./gradlew :core:compileKotlinMacosArm64 :core:jvmTest ktlintCheck`
Expected: BUILD SUCCESSFUL — the native factory + entry point compile; `DayViewSession` (commonMain) still compiles and its test passes on JVM.

- [ ] **Step 4: Commit**

```bash
git add core
git commit -m "feat(core): file-backed native DayPreferences and macOS entry point"
```

---

## Task 3: Controller `setGoalDeadlineInstant`

Add an `Instant`-based goal-deadline setter that shares the start-backfill logic with `commitGoalDeadline`. Deliverable: a tested setter, with `commitGoalDeadline` behavior unchanged.

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt` — NOT here; the controller test lives in `:composeApp` `commonTest`. Add the new test there:
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Produces: `fun DayViewController.setGoalDeadlineInstant(deadline: Instant?)`.

- [ ] **Step 1: Write the failing test**

Append to `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt` (inside the class):

```kotlin
    @Test
    fun setGoalDeadlineInstantBackfillsStartLikeCommit() {
        val controller = testController(InMemoryDayPreferences(), 10_000L)
        val deadline = t(10_000L) + 5.minutes

        controller.setGoalDeadlineInstant(deadline)

        assertEquals(deadline, controller.state.goalDeadline)
        // No prior start -> backfilled to now (initialNow = 10_000L).
        assertEquals(t(10_000L), controller.state.goalStart)
        assertEquals(formatGoalDeadline(deadline), controller.state.goalDeadlineText)
    }

    @Test
    fun setGoalDeadlineInstantNullClearsDeadline() {
        val controller = testController(InMemoryDayPreferences(), 10_000L)
        controller.setGoalDeadlineInstant(t(10_000L) + 5.minutes)

        controller.setGoalDeadlineInstant(null)

        assertEquals(null, controller.state.goalDeadline)
        assertEquals(null, controller.state.goalStart)
        assertEquals("", controller.state.goalDeadlineText)
    }
```

Add the import `import kotlin.time.Duration.Companion.minutes` if not present.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `setGoalDeadlineInstant` unresolved.

- [ ] **Step 3: Refactor the shared helper and add the setter**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`, replace `commitGoalDeadline` with a shared helper + both callers:

```kotlin
    private fun applyGoalDeadline(deadline: Instant?, deadlineText: String) {
        val existingStart = state.goalStart
        val start = when {
            deadline == null -> null
            existingStart == null || existingStart >= deadline -> state.now
            else -> existingStart
        }
        state = state.copy(
            goalDeadline = deadline,
            goalDeadlineText = deadlineText,
            goalStart = start,
            goalStartText = start?.let(::formatGoalDeadline).orEmpty(),
        )
        persistState()
    }

    fun commitGoalDeadline() {
        val parsed = parseGoalDeadline(state.goalDeadlineText)
        if (parsed == null && state.goalDeadlineText.isNotBlank()) return
        applyGoalDeadline(parsed, state.goalDeadlineText)
    }

    fun setGoalDeadlineInstant(deadline: Instant?) {
        applyGoalDeadline(deadline, deadline?.let(::formatGoalDeadline).orEmpty())
    }
```

(`commitGoalDeadline` passes the current `goalDeadlineText` back unchanged, preserving its exact prior behavior.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS — new tests pass and the existing `commitGoalDeadline` tests still pass.

- [ ] **Step 5: Verify native compile + full gate**

Run: `./gradlew :core:compileKotlinMacosArm64 ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "feat(core): add Instant-based setGoalDeadlineInstant sharing commit backfill"
```

---

## Task 4: Extend `TodaySnapshot` + `DayViewSession` with goal & focus

Add goal/focus fields to the snapshot and the matching session actions. Deliverable: tested mapping + actions.

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Modify: `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt`
- Modify: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `calculateGoalWorkingTime` (in `GlobalGoal.kt`, `:core`), `setGoalDeadlineInstant` (Task 3).
- Produces: `TodaySnapshot` fields `goalTitle: String`, `goalHasDeadline: Boolean`, `goalDeadlineEpochMillis: Long`, `goalHoursRemaining: Long`, `pomodoroMinutes: Long`; `DayViewSession` methods `setGoalTitle(title: String)`, `setGoalDeadline(epochMillis: Long)`, `setFocusIntention(intention: String)`.

- [ ] **Step 1: Write the failing tests**

In `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt`, add a test asserting the new goal/focus fields:

```kotlin
    @Test
    fun mapsGoalAndPomodoroMinutes() {
        val now = 1_700_000_000_000L
        val deadline = Instant.fromEpochMilliseconds(now) + 5.minutes
        val state = controllerWith(
            DayPreferencesSnapshot(
                startMinutes = 540,
                endMinutes = 1080,
                pomodoroMinutes = 30,
                goalTitle = "Ship it",
                goalDeadline = deadline,
            ),
            now,
        ).stateFlow.value

        val snap = state.toTodaySnapshot()

        assertEquals("Ship it", snap.goalTitle)
        assertEquals(true, snap.goalHasDeadline)
        assertEquals(deadline.toEpochMilliseconds(), snap.goalDeadlineEpochMillis)
        assertEquals(30L, snap.pomodoroMinutes)
    }
```

In `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`, add:

```kotlin
    @Test
    fun goalActionsUpdateSnapshot() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 540, endMinutes = 1080),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.setGoalTitle("Launch")
        runCurrent()
        assertEquals("Launch", seen.last().goalTitle)

        session.setGoalDeadline(1_700_000_300_000L)
        runCurrent()
        assertEquals(true, seen.last().goalHasDeadline)

        sub.cancel()
    }
```

Add imports (`kotlin.time.Duration.Companion.minutes`) as needed.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.TodaySnapshotTest" --tests "fr.dayview.app.DayViewSessionTest"`
Expected: FAIL — new fields/methods unresolved.

- [ ] **Step 3: Extend the snapshot mapping**

In `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`, add the fields to `data class TodaySnapshot` (after `dayStatus`):

```kotlin
    val goalTitle: String,
    val goalHasDeadline: Boolean,
    val goalDeadlineEpochMillis: Long,
    val goalHoursRemaining: Long,
    val pomodoroMinutes: Long,
```

And extend `toTodaySnapshot()` (add before the closing `)`):

```kotlin
        goalTitle = goalTitle,
        goalHasDeadline = goalDeadline != null,
        goalDeadlineEpochMillis = goalDeadline?.toEpochMilliseconds() ?: 0L,
        goalHoursRemaining = goalDeadline?.let { deadline ->
            val working = calculateGoalWorkingTime(now, deadline, startMinutes, endMinutes)
            kotlin.math.ceil(working.toDouble(kotlin.time.DurationUnit.HOURS)).toLong()
        } ?: 0L,
        pomodoroMinutes = pomodoroMinutes.toLong(),
```

- [ ] **Step 4: Add the session actions**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, add to `class DayViewSession` (alongside the existing actions):

```kotlin
    fun setGoalTitle(title: String) = controller.setGoalTitle(title)

    fun setGoalDeadline(epochMillis: Long) =
        controller.setGoalDeadlineInstant(
            epochMillis.takeIf { it > 0L }?.let(Instant::fromEpochMilliseconds),
        )

    fun setFocusIntention(intention: String) = controller.setFocusIntention(intention)
```

Add `import kotlin.time.Instant` if not present.

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.TodaySnapshotTest" --tests "fr.dayview.app.DayViewSessionTest"`
Expected: PASS.

- [ ] **Step 6: Verify native compile + lint**

Run: `./gradlew :core:compileKotlinMacosArm64 ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): expose goal + focus fields and actions on the native boundary"
```

---

## Task 5: Native SwiftUI goal + focus sections with persistence

Grow the SwiftUI UI into editable goal + focus sections and verify persistence across relaunch. Deliverable: a native window where editing goal/focus persists across quit+relaunch.

**Files:**
- Modify: `macos/DayView/TodayModel.swift`
- Modify: `macos/DayView/RingView.swift`

**Interfaces:**
- Consumes: `DayViewSession` (`setGoalTitle`, `setGoalDeadline`, `setFocusIntention`, `startFocus`, `stopFocus`, `changePomodoroDuration`), `TodaySnapshot` (goal + focus fields).

- [ ] **Step 1: Rebuild the framework with the extended types**

Run: `./gradlew :core:syncXCFramework`
Expected: BUILD SUCCESSFUL; the xcframework now exports the goal/focus fields and actions.

- [ ] **Step 2: Add passthroughs to the model**

In `macos/DayView/TodayModel.swift`, add to `TodayModel` (alongside `startFocus`/`stopFocus`):

```swift
    func setFocusIntention(_ text: String) { session.setFocusIntention(intention: text) }
    func changePomodoroDuration(_ delta: Int32) { session.changePomodoroDuration(deltaMinutes: delta) }
    func startFocus(intention: String) { session.startFocus(intention: intention) }
    func setGoalTitle(_ title: String) { session.setGoalTitle(title: title) }
    func setGoalDeadline(epochMillis: Int64) { session.setGoalDeadline(epochMillis: epochMillis) }
    func clearGoalDeadline() { session.setGoalDeadline(epochMillis: 0) }
```

Replace the existing hardcoded `startFocus()` (which passed `"Ship it"`) with the `startFocus(intention:)` above; the view now supplies the intention.

- [ ] **Step 3: Rewrite `RingView` with focus + goal sections**

Replace the contents of `macos/DayView/RingView.swift` with:

```swift
import SwiftUI
import DayViewKit

struct RingView: View {
    @StateObject private var model = TodayModel()
    @State private var intention: String = ""
    @State private var goalTitle: String = ""
    @State private var deadline: Date = Date()
    @State private var seeded = false

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                ringSection
                focusSection
                goalSection
            }
            .padding(32)
        }
        .onReceive(model.$snapshot) { snap in
            // Seed the local text/date fields once from persisted state.
            if !seeded {
                intention = snap.focusIntention
                goalTitle = snap.goalTitle
                if snap.goalHasDeadline {
                    deadline = Date(timeIntervalSince1970: Double(snap.goalDeadlineEpochMillis) / 1000)
                }
                seeded = true
            }
        }
    }

    private var ringSection: some View {
        VStack(spacing: 8) {
            Canvas { context, size in
                let inset: CGFloat = 40
                let side = min(size.width, size.height) - inset * 2
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = max(side / 2, 1)
                let lineWidth: CGFloat = 18
                var track = Path()
                track.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
                context.stroke(track, with: .color(.gray.opacity(0.2)), lineWidth: lineWidth)
                var sweep = Path()
                sweep.addArc(center: center, radius: radius, startAngle: .degrees(-90), endAngle: .degrees(model.snapshot.momentAngleDegrees), clockwise: false)
                context.stroke(sweep, with: .color(.accentColor), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            }
            .frame(height: 260)
            Text(model.snapshot.dayStatus)
                .font(.system(size: 40, weight: .semibold, design: .rounded))
                .monospacedDigit()
        }
    }

    private var focusSection: some View {
        GroupBox("Focus") {
            VStack(alignment: .leading, spacing: 12) {
                TextField("What are you focusing on?", text: $intention)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { model.setFocusIntention(intention) }
                HStack {
                    Stepper("Duration: \(model.snapshot.pomodoroMinutes) min",
                            onIncrement: { model.changePomodoroDuration(5) },
                            onDecrement: { model.changePomodoroDuration(-5) })
                    Spacer()
                    if model.snapshot.pomodoroStatus == "IDLE" {
                        Button("Start focus") {
                            model.setFocusIntention(intention)
                            model.startFocus(intention: intention)
                        }
                        .disabled(intention.isEmpty)
                    } else {
                        Button("Stop focus") { model.stopFocus() }
                    }
                }
                Text(focusText).foregroundStyle(.secondary)
            }
        }
    }

    private var goalSection: some View {
        GroupBox("Goal") {
            VStack(alignment: .leading, spacing: 12) {
                TextField("Goal title", text: $goalTitle)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { model.setGoalTitle(goalTitle) }
                HStack {
                    DatePicker("Deadline", selection: $deadline)
                        .onChange(of: deadline) { _, newValue in
                            model.setGoalDeadline(epochMillis: Int64(newValue.timeIntervalSince1970 * 1000))
                        }
                    if model.snapshot.goalHasDeadline {
                        Button("Clear") { model.clearGoalDeadline() }
                    }
                }
                if model.snapshot.goalHasDeadline {
                    Text("\(model.snapshot.goalHoursRemaining)h of working time left")
                        .foregroundStyle(.secondary)
                }
            }
        }
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

- [ ] **Step 4: Build**

Run:

```bash
cd macos && xcodegen generate && cd -
xcodebuild -project macos/DayView.xcodeproj -scheme DayView -configuration Debug -derivedDataPath macos/build build
```

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 5: Verify persistence across relaunch**

```bash
pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true
open macos/build/Build/Products/Debug/DayView.app
```

Wait ~3s; screenshot to the scratchpad and Read it to confirm the Focus and Goal sections render. Then set a focus intention (type + press Return), bump the duration, type a goal title (press Return), and pick a deadline — screenshot to confirm the goal readout appears. Then relaunch and confirm the values persisted:

```bash
pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true
open macos/build/Build/Products/Debug/DayView.app
```

Wait ~3s, screenshot, Read it, and confirm the intention, duration, goal title, and deadline are still present (loaded from `~/Library/Application Support/DayView/dayview.preferences_pb`). Confirm the file exists:

```bash
ls -la ~/Library/Application\ Support/DayView/
```

Close the app: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

If GUI text entry cannot be automated (accessibility wall), note it and instead confirm persistence by: launching once, and separately verifying the round-trip is covered by `:core:macosArm64Test` (Task 1) + the file being created on launch. Report exactly what was and wasn't verified.

- [ ] **Step 6: Commit**

```bash
git add macos/DayView/TodayModel.swift macos/DayView/RingView.swift
git commit -m "feat(macos): native goal and focus editing with persistence"
```

---

## Self-Review Notes

- **Spec coverage:** persistence move → Task 1; native factory + entry point → Task 2; `setGoalDeadlineInstant` (Instant path) → Task 3; `TodaySnapshot`/`DayViewSession` goal+focus extensions → Task 4; native SwiftUI goal/focus UI + persist-across-relaunch → Task 5. Done criteria (store test on JVM+native, native factory compiles, gate green, xcframework/xcodebuild, edit→relaunch→persist) all map to explicit steps.
- **Primary risk retired first:** Task 1's native round-trip test (`:core:macosArm64Test`) proves DataStore works on Kotlin/Native before any UI is built, with an explicit BLOCKED fallback (NSUserDefaults) if the artifact is missing.
- **Type consistency:** `TodaySnapshot` new fields (`goalTitle`, `goalHasDeadline`, `goalDeadlineEpochMillis: Long`, `goalHoursRemaining: Long`, `pomodoroMinutes: Long`) are consistent across Tasks 4 and 5; `DayViewSession` method names (`setGoalTitle`, `setGoalDeadline(epochMillis)`, `setFocusIntention`) match between the Kotlin definitions (Task 4) and the Swift calls (Task 5); `setGoalDeadlineInstant` is defined in Task 3 and consumed by `setGoalDeadline` in Task 4.
- **Compose path untouched:** `commitGoalDeadline` passes its current `goalDeadlineText` through the shared helper, preserving exact prior behavior (Task 3).
- **Placeholder scan:** no TBD/TODO; every code step carries complete content.
