# macOS Native Settings Scene Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A standard macOS Settings scene (⌘,) for day start/end, seconds display, and appearance — plus the presentation-label consolidation that moves the seconds line, focus status line, and menu-bar title into Kotlin and shares the closure-button row.

**Architecture:** `TodaySnapshot` gains seven fields (day window, seconds flag, theme string, and three Kotlin-computed labels) and `DayViewSession` four setters delegating to existing, already-clamping controller methods (Task 1). Swift views then consume the labels and a shared `FocusClosureButtons` replaces the duplicated outcome row (Task 2). Finally the `Settings` scene binds get-from-snapshot / set-through-bridge controls, and the seconds line + `preferredColorScheme` land on the windows (Task 3).

**Tech Stack:** Kotlin Multiplatform (`:core`), SwiftUI (macOS 15+), the `DayViewKit` XCFramework bridge.

## Global Constraints

- NO controller logic changes — `setStartMinutes`/`setEndMinutes`/`setShowSeconds`/`setThemeMode` are called as-is; their clamping (start into `0..(end−30)`, end into `(start+30)..1439`) is the validation story.
- Theme strings are exactly `"SYSTEM"`, `"LIGHT"`, `"DARK"`; unknown maps defensively to `SYSTEM` (no FFI throw). Closure outcome strings stay exactly `"COMPLETED"`/`"PROGRESSED"`/`"TO_RESUME"`.
- Snapshot conventions: numbers as `Long`, enum-likes and display text as `String`. `TodaySnapshot` is constructed ONLY in `toTodaySnapshot` (verified) — new fields need no other call-site updates.
- Label semantics (exact): `secondsLabel` = zero-padded seconds component + `"s"` (e.g. `"07s"`) when `showSeconds && !isFinished`, else `""`; `focusLine` = `"Focus · <intention> · <clock>"` (ACTIVE) / `"Break · <clock>"` (BREAK) / `""` (IDLE); `menuBarTitle` = the clock during ACTIVE/BREAK, else `dayStatus`. `dayStatus` itself is unchanged ("5h 09m" / "Day over").
- `MiniView` keeps its split compact focus layout (it does NOT consume `focusLine`); raw `pomodoroStatus`/`focusIntention`/`pomodoroClock` fields remain for structural decisions.
- Native UI copy hardcoded English. One shared `TodayModel` — never construct a second.
- Kotlin lint: `./gradlew ktlintCheck` must pass for any Kotlin change.
- Commit messages: English, imperative, change-only; no Claude/Anthropic/AI references, no Co-Authored-By. Commits succeed unsigned.
- Build/run: `./gradlew :core:runMacNative`. Headless GUI clicking is blocked — interactive checks are a manual smoke test; report exactly what was and wasn't verified.
- **Before Task 1:** create the working branch: `git checkout -b claude/macos-native-settings`.

## File map

- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` — 7 new fields + computation.
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` — 4 new setters.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` — 4 new tests.
- Create: `macos/DayView/FocusClosureButtons.swift` — shared outcome row.
- Create: `macos/DayView/ThemeScheme.swift` — themeMode → `ColorScheme?` helper.
- Create: `macos/DayView/SettingsView.swift` — the Settings form.
- Modify: `macos/DayView/TodayModel.swift`, `macos/DayView/DayViewApp.swift`, `macos/DayView/RingView.swift`, `macos/DayView/MenuBarContent.swift`, `macos/DayView/MiniView.swift`.

---

## Task 1: `:core` — snapshot fields, presentation labels, and settings setters (TDD)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: existing `DayViewController` setters `setStartMinutes(Int)`, `setEndMinutes(Int)`, `setShowSeconds(Boolean)`, `setThemeMode(ThemeMode)`; `DayViewUiState` fields `startMinutes`/`endMinutes`/`showSeconds`/`themeMode`; `DayProgress.remainingSeconds` (already the mod-60 component).
- Produces (Tasks 2–3 rely on these): snapshot fields `startMinutes: Long`, `endMinutes: Long`, `showSeconds: Boolean`, `themeMode: String`, `secondsLabel: String`, `focusLine: String`, `menuBarTitle: String`; session methods `setDayStart(minutes: Int)`, `setDayEnd(minutes: Int)`, `setShowSeconds(enabled: Boolean)`, `setThemeMode(mode: String)` (Swift: `setDayStart(minutes:)` etc., `Int` surfacing as `Int32`).

- [ ] **Step 1: Write the failing tests**

Append inside the `DayViewSessionTest` class in `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`. Add `import kotlin.test.assertTrue` to the imports (the others are present).

```kotlin
    @Test
    fun dayWindowSettersRoundTripAndClamp() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 540, endMinutes = 1080),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.setDayStart(600)
        runCurrent()
        assertEquals(600L, seen.last().startMinutes)

        session.setDayEnd(1200)
        runCurrent()
        assertEquals(1200L, seen.last().endMinutes)

        // Controller clamping is the validation story: start is coerced to end - 30.
        session.setDayStart(1439)
        runCurrent()
        assertEquals(1170L, seen.last().startMinutes)

        sub.cancel()
    }

    @Test
    fun showSecondsDrivesSecondsLabel() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            // Full-day window so the day is running at any host time zone.
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals(true, seen.last().showSeconds)
        assertTrue(Regex("^\\d{2}s$").matches(seen.last().secondsLabel))

        session.setShowSeconds(false)
        runCurrent()
        assertEquals(false, seen.last().showSeconds)
        assertEquals("", seen.last().secondsLabel)

        sub.cancel()
    }

    @Test
    fun themeModeSetterMapsAndDefaults() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals("SYSTEM", seen.last().themeMode)

        session.setThemeMode("DARK")
        runCurrent()
        assertEquals("DARK", seen.last().themeMode)

        session.setThemeMode("garbage")
        runCurrent()
        assertEquals("SYSTEM", seen.last().themeMode)

        sub.cancel()
    }

    @Test
    fun presentationLabelsFollowFocusState() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertEquals("", seen.last().focusLine)
        assertEquals(seen.last().dayStatus, seen.last().menuBarTitle)

        session.startFocus("Ship it")
        runCurrent()
        assertTrue(seen.last().focusLine.startsWith("Focus · Ship it · "))
        assertEquals(seen.last().pomodoroClock, seen.last().menuBarTitle)

        session.stopFocus()
        runCurrent()
        assertEquals("", seen.last().focusLine)
        assertEquals(seen.last().dayStatus, seen.last().menuBarTitle)

        sub.cancel()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: FAIL to compile — `unresolved reference` for `setDayStart` / `startMinutes` / `secondsLabel` etc. (compile errors are the failing state for fields/methods that don't exist yet).

- [ ] **Step 3: Add the snapshot fields and label computation**

Replace the entire contents of `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` with:

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
    // "IDLE" | "ACTIVE" | "BREAK" — the Swift UI switches on these exact strings, so
    // renaming PomodoroStatus enum constants would silently degrade the Swift UI
    // instead of failing to compile.
    val pomodoroStatus: String,
    val pomodoroClock: String, // formatted clock, "" when idle
    val focusIntention: String,
    val dayStatus: String, // remaining-time headline or "Day over"
    val goalTitle: String,
    val goalHasDeadline: Boolean,
    val goalDeadlineEpochMillis: Long,
    val goalHoursRemaining: Long,
    val pomodoroMinutes: Long,
    val startMinutes: Long, // day window start, minutes from midnight
    val endMinutes: Long, // day window end, minutes from midnight
    val showSeconds: Boolean,
    val themeMode: String, // "SYSTEM" | "LIGHT" | "DARK" (ThemeMode.name)
    // Presentation labels, computed once here instead of per-Swift-view (the
    // dayStatus/pomodoroClock convention):
    val secondsLabel: String, // e.g. "07s" when showSeconds && !isFinished, else ""
    val focusLine: String, // "Focus · <intention> · <clock>" / "Break · <clock>" / ""
    val menuBarTitle: String, // the clock during ACTIVE/BREAK, else dayStatus
)

internal fun DayViewUiState.toTodaySnapshot(): TodaySnapshot {
    val progress = dayProgress
    val pomodoro = pomodoroProgress
    val clock = when (pomodoro.status) {
        PomodoroStatus.ACTIVE -> formatPomodoroClock(pomodoro)
        PomodoroStatus.BREAK -> formatBreakClock(pomodoro)
        PomodoroStatus.IDLE -> ""
    }
    // TODO: localize — hardcoded English until the native macOS UI gains i18n.
    val status = if (progress.isFinished) {
        "Day over"
    } else {
        "${progress.remainingHours}h ${progress.remainingMinutes.toString().padStart(2, '0')}m"
    }
    return TodaySnapshot(
        remainingSeconds = progress.remaining.inWholeSeconds,
        remainingRatio = progress.remainingRatio.toDouble(),
        momentAngleDegrees = currentMomentAngleDegrees(progress.remainingRatio).toDouble(),
        isFinished = progress.isFinished,
        remainingHours = progress.remainingHours,
        remainingMinutes = progress.remainingMinutes,
        pomodoroStatus = pomodoro.status.name,
        pomodoroClock = clock,
        focusIntention = focusIntention,
        dayStatus = status,
        goalTitle = goalTitle,
        goalHasDeadline = goalDeadline != null,
        goalDeadlineEpochMillis = goalDeadline?.toEpochMilliseconds() ?: 0L,
        goalHoursRemaining = goalDeadline?.let { deadline ->
            val working = calculateGoalWorkingTime(now, deadline, startMinutes, endMinutes)
            kotlin.math.ceil(working.toDouble(kotlin.time.DurationUnit.HOURS)).toLong()
        } ?: 0L,
        pomodoroMinutes = pomodoroMinutes.toLong(),
        startMinutes = startMinutes.toLong(),
        endMinutes = endMinutes.toLong(),
        showSeconds = showSeconds,
        themeMode = themeMode.name,
        secondsLabel = if (showSeconds && !progress.isFinished) {
            "${progress.remainingSeconds.toString().padStart(2, '0')}s"
        } else {
            ""
        },
        focusLine = when (pomodoro.status) {
            PomodoroStatus.ACTIVE -> "Focus · $focusIntention · $clock"
            PomodoroStatus.BREAK -> "Break · $clock"
            PomodoroStatus.IDLE -> ""
        },
        menuBarTitle = if (pomodoro.status == PomodoroStatus.IDLE) status else clock,
    )
}
```

(This is the existing file with: `pomodoroClock`/`dayStatus` hoisted into `clock`/`status` locals so the new labels reuse them, and the seven new fields. `DayProgress.remainingSeconds` is already the mod-60 component — do not re-derive it. Everything else is byte-identical.)

- [ ] **Step 4: Add the bridge setters**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, add after the `closeFocus` method:

```kotlin
    fun setDayStart(minutes: Int) = controller.setStartMinutes(minutes)

    fun setDayEnd(minutes: Int) = controller.setEndMinutes(minutes)

    fun setShowSeconds(enabled: Boolean) = controller.setShowSeconds(enabled)

    /**
     * [mode] is "SYSTEM"/"LIGHT"/"DARK" (string-typed for the primitives-only Swift
     * facade); anything else degrades to SYSTEM rather than throwing across the FFI
     * boundary.
     */
    fun setThemeMode(mode: String) {
        controller.setThemeMode(
            when (mode) {
                "LIGHT" -> ThemeMode.LIGHT
                "DARK" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            },
        )
    }
```

(No new imports: `ThemeMode` is in the same package.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: PASS (all tests in the class, including the four new ones).

- [ ] **Step 6: Lint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (run `./gradlew ktlintFormat` and re-check if it flags your files).

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): expose day window, theme, and presentation labels on the native bridge"
```

---

## Task 2: Swift consolidation — consume the Kotlin labels, share the closure row

Behaviour-preserving refactor: the three Swift status-`switch`es are replaced by the snapshot labels, and the duplicated outcome row becomes one shared view. Deliverable: the app builds and renders exactly as before.

**Files:**
- Create: `macos/DayView/FocusClosureButtons.swift`
- Modify: `macos/DayView/DayViewApp.swift`
- Modify: `macos/DayView/RingView.swift`
- Modify: `macos/DayView/MenuBarContent.swift`
- Modify: `macos/DayView/MiniView.swift`

**Interfaces:**
- Consumes: `snapshot.focusLine`, `snapshot.menuBarTitle` from Task 1; existing `TodayModel.closeFocus(_:)`.
- Produces: `FocusClosureButtons(model: TodayModel)` — used by both windows (Task 3 doesn't change it).

- [ ] **Step 1: Create the shared closure row**

Create `macos/DayView/FocusClosureButtons.swift`:

```swift
import SwiftUI

/// The closure ritual's outcome row — Completed / Progressed / Resume later — shared by
/// the main window's focus section and the mini window's focus card.
struct FocusClosureButtons: View {
    @ObservedObject var model: TodayModel

    var body: some View {
        HStack(spacing: 8) {
            Button("Completed") { model.closeFocus("COMPLETED") }
                .buttonStyle(.bordered)
                .tint(.green)
            Button("Progressed") { model.closeFocus("PROGRESSED") }
                .buttonStyle(.bordered)
                .tint(.orange)
            Button("Resume later") { model.closeFocus("TO_RESUME") }
                .buttonStyle(.bordered)
        }
    }
}
```

- [ ] **Step 2: Menu-bar title from the snapshot**

In `macos/DayView/DayViewApp.swift`:
1. Change `MenuBarExtra(menuBarTitle) {` to `MenuBarExtra(model.snapshot.menuBarTitle) {`.
2. Delete the whole `private var menuBarTitle: String { ... }` computed property **and** the two comment lines above it (`// Live menu-bar readout: ...` / `// remaining-time headline. ...`).

Nothing else in the file changes.

- [ ] **Step 3: `RingView` consumes `focusLine` and the shared row**

In `macos/DayView/RingView.swift`:
1. Replace the line `Text(focusText).foregroundStyle(.secondary)` with:

```swift
                Text(model.snapshot.focusLine.isEmpty ? "Idle" : model.snapshot.focusLine)
                    .foregroundStyle(.secondary)
```

2. In `closureSection`, replace the entire `HStack(spacing: 8) { ... }` block (the three outcome buttons) with:

```swift
            FocusClosureButtons(model: model)
```

3. Delete the whole `private var focusText: String { ... }` computed property.

Nothing else changes.

- [ ] **Step 4: `MenuBarContent` consumes `focusLine`**

In `macos/DayView/MenuBarContent.swift`:
1. Replace:

```swift
        if let focusLine {
            Text(focusLine)
        }
```

with:

```swift
        if !model.snapshot.focusLine.isEmpty {
            Text(model.snapshot.focusLine)
        }
```

2. Delete the whole `private var focusLine: String? { ... }` computed property **and** the two comment lines above it (`// Mirrors RingView.focusText: ...` / `// the pause ...`).

Nothing else changes.

- [ ] **Step 5: `MiniView` uses the shared row**

In `macos/DayView/MiniView.swift`, inside `focusCard`'s `case "BREAK":`, replace the entire second `HStack(spacing: 8) { ... }` block (the three outcome buttons with `.bordered`/tints) with:

```swift
                FocusClosureButtons(model: model)
```

Nothing else changes (the mini keeps its split "Break · intention" + clock layout by design).

- [ ] **Step 6: Build and launch — behaviour unchanged**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches; menu-bar text, focus status line, and (if you can reach a break manually) the closure buttons render as before. Close it: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 7: Commit**

```bash
git add macos/DayView/FocusClosureButtons.swift macos/DayView/DayViewApp.swift macos/DayView/RingView.swift macos/DayView/MenuBarContent.swift macos/DayView/MiniView.swift
git commit -m "refactor(macos): consume Kotlin presentation labels and share the closure-button row"
```

---

## Task 3: Settings scene, seconds line, and appearance

**Files:**
- Create: `macos/DayView/ThemeScheme.swift`
- Create: `macos/DayView/SettingsView.swift`
- Modify: `macos/DayView/TodayModel.swift`
- Modify: `macos/DayView/DayViewApp.swift`
- Modify: `macos/DayView/RingView.swift`
- Modify: `macos/DayView/MiniView.swift`

**Interfaces:**
- Consumes: session setters from Task 1 (Swift: `setDayStart(minutes: Int32)`, `setDayEnd(minutes: Int32)`, `setShowSeconds(enabled: Bool)`, `setThemeMode(mode: String)`); snapshot fields `startMinutes`/`endMinutes` (`Int64` in Swift), `showSeconds`, `themeMode`, `secondsLabel`.
- Produces: `SettingsView(model:)`, `preferredScheme(for: String) -> ColorScheme?`.

- [ ] **Step 1: `TodayModel` passthroughs**

In `macos/DayView/TodayModel.swift`, add after `func closeFocus(_ outcome: String) { ... }`:

```swift
    func setDayStart(minutes: Int32) { session.setDayStart(minutes: minutes) }
    func setDayEnd(minutes: Int32) { session.setDayEnd(minutes: minutes) }
    func setShowSeconds(_ enabled: Bool) { session.setShowSeconds(enabled: enabled) }
    func setThemeMode(_ mode: String) { session.setThemeMode(mode: mode) }
```

- [ ] **Step 2: Theme helper**

Create `macos/DayView/ThemeScheme.swift`:

```swift
import SwiftUI

/// Maps the snapshot's themeMode string to SwiftUI. "SYSTEM" (or anything unknown) → nil,
/// which lets the window follow the OS appearance.
func preferredScheme(for themeMode: String) -> ColorScheme? {
    switch themeMode {
    case "LIGHT": return .light
    case "DARK": return .dark
    default: return nil
    }
}
```

- [ ] **Step 3: The Settings form**

Create `macos/DayView/SettingsView.swift`:

```swift
import SwiftUI
import DayViewKit

/// Standard macOS Settings window (app menu → Settings…, ⌘,). Every control binds
/// get-from-snapshot / set-through-bridge — no local state and no save button; the
/// controller's clamping round-trips back into the pickers via the snapshot.
struct SettingsView: View {
    @ObservedObject var model: TodayModel

    var body: some View {
        Form {
            Section("Day") {
                DatePicker(
                    "Start",
                    selection: minutesBinding(get: { $0.startMinutes }, set: { model.setDayStart(minutes: $0) }),
                    displayedComponents: .hourAndMinute
                )
                DatePicker(
                    "End",
                    selection: minutesBinding(get: { $0.endMinutes }, set: { model.setDayEnd(minutes: $0) }),
                    displayedComponents: .hourAndMinute
                )
            }
            Section("Display") {
                Toggle(
                    "Show seconds",
                    isOn: Binding(
                        get: { model.snapshot.showSeconds },
                        set: { model.setShowSeconds($0) }
                    )
                )
                Picker(
                    "Appearance",
                    selection: Binding(
                        get: { model.snapshot.themeMode },
                        set: { model.setThemeMode($0) }
                    )
                ) {
                    Text("System").tag("SYSTEM")
                    Text("Light").tag("LIGHT")
                    Text("Dark").tag("DARK")
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 360)
        .preferredColorScheme(preferredScheme(for: model.snapshot.themeMode))
    }

    // Binds a minutes-from-midnight preference to an hour-and-minute DatePicker. Uses a
    // fixed anchor day and round-trips through Calendar components, so DST on any real
    // day cannot corrupt the minutes.
    private func minutesBinding(
        get: @escaping (TodaySnapshot) -> Int64,
        set: @escaping (Int32) -> Void
    ) -> Binding<Date> {
        Binding(
            get: {
                let minutes = Int(get(model.snapshot))
                var components = DateComponents()
                components.year = 2001
                components.month = 1
                components.day = 1
                components.hour = minutes / 60
                components.minute = minutes % 60
                return Calendar.current.date(from: components) ?? Date()
            },
            set: { newValue in
                let components = Calendar.current.dateComponents([.hour, .minute], from: newValue)
                set(Int32((components.hour ?? 0) * 60 + (components.minute ?? 0)))
            }
        )
    }
}
```

- [ ] **Step 4: Register the scene and apply the appearance**

In `macos/DayView/DayViewApp.swift`:

1. In `DayViewApp.body`, add a `Settings` scene after the mini `Window` scene's modifiers (i.e. after `.commandsRemoved()`):

```swift
        Settings {
            SettingsView(model: model)
        }
```

2. In `MainWindowRoot.body`, add after `.onDisappear { windows.isMainOpen = false }`:

```swift
        .preferredColorScheme(preferredScheme(for: model.snapshot.themeMode))
```

3. In the mini `Window` scene's content, add the same modifier after `.onDisappear { windows.isMiniOpen = false }`:

```swift
                .preferredColorScheme(preferredScheme(for: model.snapshot.themeMode))
```

- [ ] **Step 5: Seconds line in both windows**

1. In `macos/DayView/RingView.swift`, in `ringSection`, add after the `Text(model.snapshot.dayStatus)...monospacedDigit()` view:

```swift
            if !model.snapshot.secondsLabel.isEmpty {
                Text(model.snapshot.secondsLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
```

2. In `macos/DayView/MiniView.swift`, add the same block after its countdown `Text(model.snapshot.dayStatus)...monospacedDigit()` view:

```swift
                        if !model.snapshot.secondsLabel.isEmpty {
                            Text(model.snapshot.secondsLabel)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
```

- [ ] **Step 6: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches; a seconds line ("NNs") ticks under the countdown (seconds default to on). Confirm the process is alive (`pgrep -f 'Debug/DayView.app'`).

- [ ] **Step 7: Verify what the environment allows, report the rest as manual**

The interactive checks are the **manual smoke test** (GUI clicks blocked in-sandbox) — report them as not-verified:

1. App menu → "Settings…" (⌘,) opens the form with Day / Display sections.
2. Moving the Start/End pickers reshapes the ring immediately; setting Start after End snaps back to the clamped value.
3. The "Show seconds" toggle shows/hides the seconds line in both the main window and the mini.
4. Appearance Light/Dark forces the windows' appearance; System follows the OS.
5. Menu-bar title and focus line still behave as before (Task 2 refactor is invisible).

Close afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 8: Commit**

```bash
git add macos/DayView/ThemeScheme.swift macos/DayView/SettingsView.swift macos/DayView/TodayModel.swift macos/DayView/DayViewApp.swift macos/DayView/RingView.swift macos/DayView/MiniView.swift
git commit -m "feat(macos): native settings scene for day window, seconds, and appearance"
```

---

## Self-Review Notes

- **Spec coverage:** seven snapshot fields + four setters + defensive theme mapping → Task 1 Steps 3–4 (pinned by four tests incl. clamping, label gating, and the garbage-string default); presentation-label consolidation (menuBarTitle, focusLine with RingView's "Idle" fallback, shared `FocusClosureButtons`, MiniView keeps split layout) → Task 2; `Settings` scene with snapshot-bound controls (no local state), Date↔minutes via fixed-anchor components, seconds line in both windows via `secondsLabel`, `preferredColorScheme` on the three roots → Task 3; testing/done criteria and the manual smoke list → Task 1 Steps 2/5 and Task 3 Step 7.
- **Type consistency:** Kotlin `Int` setter params surface as `Int32` in Swift — `TodayModel` takes `Int32` and `SettingsView` casts once; snapshot `Long` fields surface as `Int64` and `minutesBinding`'s getter takes `(TodaySnapshot) -> Int64`; theme strings `"SYSTEM"`/`"LIGHT"`/`"DARK"` consistent across the setter, the Picker tags, and `preferredScheme`; `FocusClosureButtons(model:)` matches both call sites.
- **No placeholders:** every code step carries complete code; `TodaySnapshot.kt` is a full-file replacement, Swift edits are anchored to exact existing lines.
- **YAGNI:** no sounds/net-time/on-goal/sync/font-scale controls; no `isBreak` booleans; MiniView deliberately not forced onto `focusLine`.
- **Known nuance:** `menuBarTitle` during BREAK shows the break clock (same as today's Swift behaviour — no change).
