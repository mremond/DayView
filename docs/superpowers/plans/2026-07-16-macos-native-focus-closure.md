# macOS Native Focus-Closure Ritual Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the "Completed / Progressed / Resume later" focus-closure ritual to the native macOS app — exposed on the `:core` session bridge and rendered in the main window's break state.

**Architecture:** `DayViewController.closePomodoro(outcome)` already exists and is fully tested; this plan only adds a primitives-only `closeFocus(outcome: String)` passthrough on `DayViewSession` (Task 1), then mirrors it on `TodayModel` and renders the ritual in `RingView`'s break state with an intention re-sync (Task 2).

**Tech Stack:** Kotlin Multiplatform (`:core`, commonMain/commonTest), SwiftUI (macOS 13+), the `DayViewKit` XCFramework bridge.

## Global Constraints

- Outcome strings are exactly `"COMPLETED"`, `"PROGRESSED"`, `"TO_RESUME"`; any other string maps defensively to `COMPLETED` (no throw across the FFI boundary).
- NO controller logic changes — `closePomodoro` is called as-is. No new `TodaySnapshot` field (the UI keys off the existing `pomodoroStatus == "BREAK"` and `focusIntention`).
- Closure UI appears during BREAK only; ACTIVE keeps the existing outcome-less **Stop** (early abort), IDLE keeps the existing Start + stepper.
- Native UI copy is hardcoded English (the native macOS app has no i18n yet) — this matches the existing `RingView` strings.
- Commit messages: English, imperative, change-only; no Claude/Anthropic/AI references, no Co-Authored-By. Commit signing is disabled locally; commits succeed unsigned.
- Kotlin lint: `./gradlew ktlintCheck` must pass for any Kotlin change.
- Native build/run: `./gradlew :core:runMacNative` (syncs the XCFramework, regenerates the Xcode project via xcodegen, builds, launches). Headless GUI clicking is blocked in this environment — button behaviour is a manual smoke test; report exactly what was and wasn't verified.
- **Before Task 1:** create the working branch from the current commit: `git checkout -b claude/macos-native-focus-closure`.

## File map

- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` — add `closeFocus(outcome: String)`.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` — three new tests.
- Modify: `macos/DayView/TodayModel.swift` — add `closeFocus(_:)` passthrough.
- Modify: `macos/DayView/RingView.swift` — break-state relaunch + closure section + intention re-sync.

---

## Task 1: `closeFocus(outcome: String)` on the `:core` session bridge

Expose the existing controller closure on the native bridge, TDD. Deliverable: `DayViewSession.closeFocus` exists, mapped and pinned by tests under `:core:jvmTest`.

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `DayViewController.closePomodoro(outcome: FocusClosureOutcome)` (exists, tested), `FocusClosureOutcome` enum (`COMPLETED`/`PROGRESSED`/`TO_RESUME` in `core/src/commonMain/kotlin/fr/dayview/app/Pomodoro.kt`).
- Produces: `fun closeFocus(outcome: String)` on `DayViewSession` — Task 2's Swift code calls it as `session.closeFocus(outcome: ...)`.

- [ ] **Step 1: Write the failing tests**

Append these three tests inside the existing `DayViewSessionTest` class in `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` (the imports it needs — `runTest`, `runCurrent`, `Instant`, `assertEquals` — are already present in the file):

```kotlin
    @Test
    fun closeFocusCompletedEndsSessionAndClearsIntention() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.startFocus("Ship it")
        runCurrent()
        assertEquals("ACTIVE", seen.last().pomodoroStatus)

        session.closeFocus("COMPLETED")
        runCurrent()
        assertEquals("IDLE", seen.last().pomodoroStatus)
        assertEquals("", seen.last().focusIntention)

        sub.cancel()
    }

    @Test
    fun closeFocusToResumeKeepsIntention() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.startFocus("Ship it")
        runCurrent()
        session.closeFocus("TO_RESUME")
        runCurrent()
        assertEquals("IDLE", seen.last().pomodoroStatus)
        assertEquals("Ship it", seen.last().focusIntention)

        sub.cancel()
    }

    @Test
    fun closeFocusUnknownOutcomeDefaultsToCompleted() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            initialNow = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.startFocus("Ship it")
        runCurrent()
        session.closeFocus("garbage")
        runCurrent()
        // COMPLETED semantics: session ends, intention cleared.
        assertEquals("IDLE", seen.last().pomodoroStatus)
        assertEquals("", seen.last().focusIntention)

        sub.cancel()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: FAIL to compile with `unresolved reference: closeFocus` (a compile error in the test source is the failing state for a bridge method that doesn't exist yet).

- [ ] **Step 3: Implement `closeFocus` on the bridge**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, add this method after `fun stopFocus() = controller.stopPomodoro()`:

```kotlin
    /**
     * Ends the session through the closure ritual. [outcome] is one of "COMPLETED",
     * "PROGRESSED", "TO_RESUME" (string-typed for the primitives-only Swift facade,
     * symmetric with TodaySnapshot.pomodoroStatus); anything else degrades to
     * COMPLETED rather than throwing across the FFI boundary.
     */
    fun closeFocus(outcome: String) {
        controller.closePomodoro(
            when (outcome) {
                "PROGRESSED" -> FocusClosureOutcome.PROGRESSED
                "TO_RESUME" -> FocusClosureOutcome.TO_RESUME
                else -> FocusClosureOutcome.COMPLETED
            },
        )
    }
```

(No new imports needed: `FocusClosureOutcome` is in the same `fr.dayview.app` package.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: PASS (all tests in the class, including the three new ones).

- [ ] **Step 5: Lint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL. If it flags formatting in the files you touched, run `./gradlew ktlintFormat` and re-check.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): expose the focus-closure ritual on the native session bridge"
```

---

## Task 2: Break-state closure ritual in the native main window

Mirror `closeFocus` on `TodayModel` and render the ritual in `RingView`'s break state: relaunch + Stop in the button row, three outcome buttons below, and the intention re-sync when a session ends. Deliverable: the native app builds and launches with the new break-state UI.

**Files:**
- Modify: `macos/DayView/TodayModel.swift`
- Modify: `macos/DayView/RingView.swift`

**Interfaces:**
- Consumes: `session.closeFocus(outcome: String)` from Task 1 (Kotlin `closeFocus(outcome: String)` surfaces to Swift as `closeFocus(outcome:)`); existing `TodaySnapshot` fields `pomodoroStatus` (`"IDLE"`/`"ACTIVE"`/`"BREAK"`), `focusIntention`; existing `TodayModel.startFocus(intention:)`/`stopFocus()`.
- Produces: `TodayModel.closeFocus(_ outcome: String)`; the break-state UI (Phase 5b's mini window will reuse the same model methods).

- [ ] **Step 1: Add the `closeFocus` passthrough to `TodayModel`**

In `macos/DayView/TodayModel.swift`, add this method after `func stopFocus() { session.stopFocus() }`:

```swift
    func closeFocus(_ outcome: String) { session.closeFocus(outcome: outcome) }
```

- [ ] **Step 2: Rewrite `RingView`'s focus section with the break-state ritual and the intention re-sync**

Replace the entire contents of `macos/DayView/RingView.swift` with:

```swift
import SwiftUI
import DayViewKit

struct RingView: View {
    @ObservedObject var model: TodayModel
    @State private var intention: String = ""
    @State private var goalTitle: String = ""
    @State private var deadline: Date = Date()
    @State private var seeded = false
    @State private var lastPomodoroStatus = "IDLE"

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
            // A session just ended (closure or stop): the persisted intention is the
            // source of truth — Completed/Progressed cleared it, Resume later kept it —
            // so re-sync the field. Scoped to the non-IDLE -> IDLE transition so it
            // never clobbers mid-typing edits during normal ticks.
            if lastPomodoroStatus != "IDLE" && snap.pomodoroStatus == "IDLE" {
                intention = snap.focusIntention
            }
            lastPomodoroStatus = snap.pomodoroStatus
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
                        .disabled(model.snapshot.pomodoroStatus != "IDLE")
                    Spacer()
                    switch model.snapshot.pomodoroStatus {
                    case "IDLE":
                        Button("Start focus") {
                            model.startFocus(intention: intention)
                        }
                        .disabled(intention.isEmpty)
                    case "BREAK":
                        // Relaunch the next session of the sequence, keeping the intention.
                        Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                        Button("Stop focus") { model.stopFocus() }
                    default: // "ACTIVE"
                        Button("Stop focus") { model.stopFocus() }
                    }
                }
                Text(focusText).foregroundStyle(.secondary)
                if model.snapshot.pomodoroStatus == "BREAK" {
                    closureSection
                }
            }
        }
    }

    // The closure ritual: name how the sequence ends so the session record and
    // clean-session ledger stay honest. Break-only; Stop stays an outcome-less abort.
    private var closureSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Close this focus")
                .font(.caption)
                .foregroundStyle(.secondary)
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

    private var goalSection: some View {
        GroupBox("Long-term goal") {
            VStack(alignment: .leading, spacing: 12) {
                TextField("Long-term goal", text: $goalTitle)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { model.setGoalTitle(goalTitle) }
                HStack {
                    DatePicker("Deadline", selection: $deadline)
                        .onChange(of: deadline) { newValue in
                            let millis = Int64(newValue.timeIntervalSince1970 * 1000)
                            // Only persist a genuine user change. Seeding `deadline` from the
                            // snapshot also fires .onChange (with `seeded` already true); guard
                            // on the value so that seed round-trip isn't written back.
                            if seeded && millis != model.snapshot.goalDeadlineEpochMillis {
                                model.setGoalDeadline(epochMillis: millis)
                            }
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

(Diff vs. the current file, for orientation only — the block above is the source of truth: one new `@State` `lastPomodoroStatus`; the re-sync block in `.onReceive`; the `if/else` in the button row becomes a `switch` adding the BREAK case with Relaunch; the conditional `closureSection` after `focusText`; the new `closureSection` property. Ring/goal sections and `focusText` are unchanged.)

- [ ] **Step 3: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **` and the app launches showing the ring + Focus/Goal sections; the Focus section shows the duration stepper + "Start focus" (IDLE state) as before.

If the Swift compiler flags the `switch` inside the `HStack`, wrap the three cases' contents in `Group { ... }` — but a plain `switch` is valid in a SwiftUI `ViewBuilder` on macOS 13+, so this should not be needed.

- [ ] **Step 4: Verify what the environment allows, report the rest as manual**

Headless GUI clicking is blocked (Accessibility), so at minimum confirm: build succeeded, the app launches, and the process stays alive (`pgrep -f 'Debug/DayView.app'` returns a PID). The behavioural checks below are the **manual smoke test** — report them as not-verified-in-sandbox:

1. Start a focus, wait for (or shorten to reach) the break → the Focus section shows "Break · MM:SS", Relaunch, Stop, and the three closure buttons.
2. "Completed" / "Progressed" → section returns to IDLE and the intention field clears.
3. "Resume later" → section returns to IDLE and the intention field keeps its text.
4. "Relaunch" during break → a new session starts with the same intention.

Close the app afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 5: Commit**

```bash
git add macos/DayView/TodayModel.swift macos/DayView/RingView.swift
git commit -m "feat(macos): focus-closure ritual in the native main window"
```

---

## Self-Review Notes

- **Spec coverage:** bridge `closeFocus(String)` with defensive mapping → Task 1 Step 3 (pinned by the three tests, including the unknown-string default); `TodayModel.closeFocus` → Task 2 Step 1; break-state ritual (three outcomes + Relaunch + Stop) → Task 2 Step 2 `focusSection`/`closureSection`; intention re-sync on the non-IDLE→IDLE transition → Task 2 Step 2 `.onReceive`; colour intent (Completed green, Progressed orange/amber, Resume later untinted/muted) → `closureSection`; testing/done criteria → Task 1 Steps 2/4 and Task 2 Steps 3/4 (manual smoke test called out, matching the spec's environment note).
- **Type consistency:** Kotlin `closeFocus(outcome: String)` ↔ Swift call `session.closeFocus(outcome: outcome)` ↔ UI calls `model.closeFocus("COMPLETED"|"PROGRESSED"|"TO_RESUME")`; status literals `"IDLE"`/`"ACTIVE"`/`"BREAK"` match `TodaySnapshot.pomodoroStatus` (`PomodoroStatus.name`).
- **No placeholders:** every code step carries the complete code; Task 2 Step 2 replaces the whole file to eliminate merge ambiguity.
- **YAGNI:** no new snapshot fields, no controller changes, no mini window, no resume ritual, no i18n — all deferred per the spec.
- **The `sessionOffGoal` limitation** is behaviour-neutral in this plan (the controller uses its own state, which stays `Duration.ZERO` natively) — documented in the spec, nothing to implement.
