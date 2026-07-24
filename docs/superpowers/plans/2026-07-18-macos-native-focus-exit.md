# macOS Native Focus Exit Implementation Plan (Phase 12a)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the native macOS app a working focus exit — every Stop routes through a closure ritual that carries the intention and, when leaving early, charges the exit toll that opens a named detour.

**Architecture:** `:core`'s controller already implements the whole asymmetric lifecycle; only the Swift-facing facade changes. `TodaySnapshot` gains a single `earlyExitCostsName` boolean plus the open-detour fields, `DayViewSession` gains a full-arity `closeFocus` and `stopOpenDetour`, and two new SwiftUI views — a modal closure sheet and an open-detour banner — replace the current inline closure buttons and the outcome-less Stop.

**Tech Stack:** Kotlin Multiplatform (`:core` commonMain / commonTest), Kotlin/Native macOS target, SwiftUI (macOS 15+), XcodeGen.

## Global Constraints

- **The controller does not change.** Every behaviour this phase needs already exists in `DayViewController`; only `TodaySnapshot`, `DayViewSession` and Swift change. A task that edits `DayViewController.kt` has gone wrong.
- **No Kotlin default parameters on bridge functions.** Phase 7b established that default parameters change the exported Swift selector; bridge signatures are full-arity and explicit.
- **Presentation labels are computed in Kotlin**, once in `toTodaySnapshot`, never per Swift view (the `dayStatus`/`pomodoroClock`/`focusTotalLabel` convention).
- **The native UI is hardcoded English.** French localization is a separate cross-cutting phase; do not add `values-fr` entries.
- **Out of scope (phase 12b):** free entry (the intention stays required to Start, no 5-minute preset), the `OVERTIME` display gaps (`MiniView`'s hardcoded `"Break · …"`, `"OVERTIME"` folded into the `"BREAK"` case, the resume ritual's `"… left to stay on track."` copy), and the overtime closure reminder. Leave all of them exactly as they are.
- **Every task leaves both builds green.** `./gradlew :core:runMacNative` must succeed at the end of each task, not only at the end of the plan.
- ktlint is enforced (`./gradlew ktlintFormat` auto-fixes most findings). Full gate before each commit:
  `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
- Commit messages: English, no reference to Claude/Anthropic/an AI assistant, no reference to `docs/superpowers/`, no test-plan or verification section.

## File Structure

| File | Responsibility |
|---|---|
| `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` (**modify**) | Five new fields: the exit-toll boolean and the four open-detour fields |
| `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` (**modify**) | Full-arity `closeFocus`, new `stopOpenDetour`, removal of `stopFocus` (Task 2) |
| `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt` (**modify**) | The new snapshot fields |
| `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` (**modify**) | The closure/toll/open-detour action paths |
| `macos/DayView/FocusClosureSheet.swift` (**create**) | The closure ritual: intention, three outcomes, unfolding exit capture |
| `macos/DayView/OpenDetourBanner.swift` (**create**) | The running open detour: motif, detail, elapsed clock, Stop |
| `macos/DayView/FocusClosureButtons.swift` (**delete**, Task 2) | Superseded by the sheet |
| `macos/DayView/TodayModel.swift` (**modify**) | Bridge wrappers |
| `macos/DayView/RingView.swift` (**modify**) | Present the sheet; show the banner; resume ritual's Stop |
| `macos/DayView/MiniView.swift` (**modify**) | Present the sheet; show the banner |
| `docs/superpowers/macos-native-parity-checklist.md` (**modify**, Task 3) | Move the closed rows to Done; record the sync follow-up |

---

### Task 1: The bridge — snapshot fields and session actions

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Modify: `macos/DayView/TodayModel.swift`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt`, `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes (all pre-existing in `:core`): `earlyExitRequiresDetour(now, end, outcome, detourAlreadyRunning)`, `formatElapsedClock(Duration)`, `DayViewUiState.openDetourRunning` / `.openDetourElapsed` / `.openDetourCategory` / `.openDetourDescription`, `DayViewController.closePomodoro(outcome, intention, detourCategory, detourDescription)`, `DayViewController.stopOpenDetour(category, description)`.
- Produces, for Tasks 2 and 3:
  - `TodaySnapshot.earlyExitCostsName: Boolean`, `.detourOpenRunning: Boolean`, `.detourOpenCategory: String`, `.detourOpenDescription: String`, `.detourOpenClock: String`
  - `DayViewSession.closeFocus(outcome: String, intention: String, detourCategory: String, detourDescription: String)` — in Swift: `closeFocus(outcome:intention:detourCategory:detourDescription:)`
  - `DayViewSession.stopOpenDetour()`
  - `TodayModel.closeFocus(_ outcome: String)` — **a temporary one-argument shim**, replaced in Task 2.

**Note on `stopFocus`:** leave `DayViewSession.stopFocus()` and `TodayModel.stopFocus()` in place for this task. Task 2 deletes them together with their five Swift call sites; removing them here would break the native build mid-plan.

---

- [ ] **Step 1: Write the failing snapshot tests**

Add to `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt`, reusing the file's existing `controllerWith(snapshot, nowMillis)` helper rather than constructing a controller inline.

The file currently imports only `assertEquals` / `assertNotEquals` and the `minutes` extension. Add these three imports:

```kotlin
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
```

Then the tests:

```kotlin
    @Test
    fun earlyExitCostsNameOnlyBeforeTheTerm() {
        val nowMillis = 1_699_956_000_000L
        val start = Instant.fromEpochMilliseconds(nowMillis)
        val controller = controllerWith(
            DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            nowMillis,
        )
        controller.startPomodoro()

        controller.tick(start + 5.minutes)
        assertTrue(
            controller.stateFlow.value.toTodaySnapshot().earlyExitCostsName,
            "mid-session, leaving costs a name",
        )

        controller.tick(start + 25.minutes)
        assertFalse(
            controller.stateFlow.value.toTodaySnapshot().earlyExitCostsName,
            "at the term the toll lifts",
        )
    }

    @Test
    fun earlyExitIsFreeWhileADetourIsAlreadyOpen() {
        val nowMillis = 1_699_956_000_000L
        val start = Instant.fromEpochMilliseconds(nowMillis)
        val controller = controllerWith(
            DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            nowMillis,
        )
        controller.startPomodoro()
        controller.tick(start + 5.minutes)
        controller.startOpenDetour("email", "inbox")

        // The running detour IS the named exit; no second name is owed.
        assertFalse(controller.stateFlow.value.toTodaySnapshot().earlyExitCostsName)
    }

    @Test
    fun openDetourFieldsRenderTheRunningDetour() {
        val nowMillis = 1_699_956_000_000L
        val start = Instant.fromEpochMilliseconds(nowMillis)
        val controller = controllerWith(
            DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            nowMillis,
        )
        val idle = controller.stateFlow.value.toTodaySnapshot()
        assertFalse(idle.detourOpenRunning)
        assertEquals("", idle.detourOpenCategory)
        assertEquals("", idle.detourOpenClock)

        controller.startOpenDetour("email", "inbox triage")
        controller.tick(start + 65.seconds)
        val running = controller.stateFlow.value.toTodaySnapshot()
        assertTrue(running.detourOpenRunning)
        assertEquals("email", running.detourOpenCategory)
        assertEquals("inbox triage", running.detourOpenDescription)
        assertEquals("01:05", running.detourOpenClock)
    }
```

- [ ] **Step 2: Run the snapshot tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.TodaySnapshotTest'`

Expected: compilation failure — `Unresolved reference: earlyExitCostsName` (and the four `detourOpen*` fields).

- [ ] **Step 3: Add the snapshot fields**

In `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`, extend the `TodaySnapshot` constructor. Replace:

```kotlin
    val showDriftReminder: Boolean,
    val showResumeRitual: Boolean,
)
```

with:

```kotlin
    val showDriftReminder: Boolean,
    val showResumeRitual: Boolean,
    // Leaving now with PROGRESSED or TO_RESUME costs a named detour. One boolean covers
    // both tolled outcomes exactly: the predicate's only outcome-dependence is
    // `outcome != COMPLETED`, so COMPLETED is always free and the other two always share
    // a verdict. It falls to false on its own at the term and while a detour is already
    // running, which is what lets the Swift capture collapse without any timing logic.
    val earlyExitCostsName: Boolean,
    val detourOpenRunning: Boolean,
    val detourOpenCategory: String,
    val detourOpenDescription: String,
    val detourOpenClock: String, // "MM:SS" elapsed, "" when none is running
)
```

Then, in `toTodaySnapshot`, replace:

```kotlin
        showDriftReminder = showDriftReminder,
        showResumeRitual = showResumeRitual,
    )
```

with:

```kotlin
        showDriftReminder = showDriftReminder,
        showResumeRitual = showResumeRitual,
        earlyExitCostsName = earlyExitRequiresDetour(
            now = now,
            end = pomodoroEnd,
            outcome = FocusClosureOutcome.PROGRESSED,
            detourAlreadyRunning = openDetourRunning,
        ),
        detourOpenRunning = openDetourRunning,
        detourOpenCategory = openDetourCategory,
        detourOpenDescription = openDetourDescription,
        detourOpenClock = if (openDetourRunning) formatElapsedClock(openDetourElapsed) else "",
    )
```

- [ ] **Step 4: Run the snapshot tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.TodaySnapshotTest'`

Expected: PASS.

- [ ] **Step 5: Write the failing session tests**

Add to `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`, at the end of the class. The file already imports `runCurrent`, `runTest`, `assertEquals`, `assertTrue`, `assertFalse`, `Instant`, and the `minutes`/`seconds` duration extensions.

```kotlin
    private fun closureController(now: Instant) = DayViewController(
        DefaultDayPreferences,
        backgroundScope,
        initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
        initialNow = now,
    )

    @Test
    fun earlyProgressedWithAMotifClosesTheSessionAndOpensTheDetour() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = closureController(start)
        val session = DayViewSession(controller, backgroundScope)
        session.startFocus("Ship it")
        controller.tick(start + 5.minutes)

        session.closeFocus("PROGRESSED", "Shipped half of it", "email", "inbox triage")
        runCurrent()

        val state = controller.stateFlow.value
        assertEquals("IDLE", state.toTodaySnapshot().pomodoroStatus, "the session closed")
        assertTrue(state.openDetourRunning, "the exit toll opened a detour")
        assertEquals("email", state.openDetourCategory)
        // The day-scoped accessor, not the raw `focusSessionRecords` field.
        assertEquals(1, state.focusSessionRecordsToday.size, "the closing session was recorded")
        assertEquals("Shipped half of it", state.focusSessionRecordsToday.last().intention)
    }

    @Test
    fun earlyProgressedWithoutAMotifChangesNothing() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = closureController(start)
        val session = DayViewSession(controller, backgroundScope)
        session.startFocus("Ship it")
        controller.tick(start + 5.minutes)

        session.closeFocus("PROGRESSED", "Ship it", "", "")
        runCurrent()

        // The controller refuses silently; the Swift sheet keeps Confirm disabled so the
        // user never reaches this path, and this asserts what happens if they did.
        val state = controller.stateFlow.value
        assertEquals("ACTIVE", state.toTodaySnapshot().pomodoroStatus, "the session kept running")
        assertFalse(state.openDetourRunning)
    }

    @Test
    fun earlyCompletedIsFreeOfAnyToll() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = closureController(start)
        val session = DayViewSession(controller, backgroundScope)
        session.startFocus("Ship it")
        controller.tick(start + 5.minutes)

        session.closeFocus("COMPLETED", "Done early", "", "")
        runCurrent()

        val state = controller.stateFlow.value
        assertEquals("IDLE", state.toTodaySnapshot().pomodoroStatus)
        assertFalse(state.openDetourRunning, "done early is done — no detour demanded")
    }

    @Test
    fun stopOpenDetourCommitsTheEpisode() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = closureController(start)
        val session = DayViewSession(controller, backgroundScope)
        controller.startOpenDetour("email", "inbox triage")
        controller.tick(start + 10.minutes)

        session.stopOpenDetour()
        runCurrent()

        val state = controller.stateFlow.value
        assertFalse(state.openDetourRunning, "the detour closed")
        assertEquals(1, state.detoursToday.size, "the episode was committed")
        assertEquals("email", state.detoursToday.last().category)
    }
```

- [ ] **Step 6: Run the session tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`

Expected: compilation failure — no `closeFocus` taking four arguments, and `Unresolved reference: stopOpenDetour`.

- [ ] **Step 7: Extend the session bridge**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, replace the whole existing `closeFocus` function (its KDoc included) with:

```kotlin
    /**
     * Ends the session through the closure ritual. [outcome] is one of "COMPLETED",
     * "PROGRESSED", "TO_RESUME" (string-typed for the primitives-only Swift facade,
     * symmetric with TodaySnapshot.pomodoroStatus); anything else degrades to
     * COMPLETED rather than throwing across the FFI boundary.
     *
     * Full arity rather than default parameters: Kotlin defaults change the exported
     * Swift selector, so a later default would silently break the Swift call sites.
     *
     * A [detourCategory] pays the exit toll — leaving before the term with PROGRESSED or
     * TO_RESUME. Without it the controller refuses the closure silently and the session
     * simply keeps running; the Swift sheet keeps Confirm disabled so that never happens.
     */
    fun closeFocus(
        outcome: String,
        intention: String,
        detourCategory: String,
        detourDescription: String,
    ) {
        controller.closePomodoro(
            when (outcome) {
                "PROGRESSED" -> FocusClosureOutcome.PROGRESSED
                "TO_RESUME" -> FocusClosureOutcome.TO_RESUME
                else -> FocusClosureOutcome.COMPLETED
            },
            intention = intention,
            detourCategory = detourCategory,
            detourDescription = detourDescription,
        )
    }

    /**
     * Closes the running open detour into an episode, reusing the motif it was opened
     * with — the same delegation the Compose app does. The controller refuses a blank
     * motif; natively one cannot occur, since only the exit toll opens a detour and it
     * always names it (see the phase 12a spec's note on what changes when sync lands).
     */
    fun stopOpenDetour() {
        controller.stopOpenDetour(controller.stateFlow.value.openDetourCategory)
    }
```

- [ ] **Step 8: Keep the Swift build green with a temporary shim**

`TodayModel.closeFocus(_:)` calls the one-argument bridge function that no longer exists. In `macos/DayView/TodayModel.swift`, replace:

```swift
    func closeFocus(_ outcome: String) { session.closeFocus(outcome: outcome) }
```

with:

```swift
    // Temporary shim: preserves today's behaviour (the controller's own default was the
    // persisted intention) until the closure sheet supplies the real values.
    func closeFocus(_ outcome: String) {
        session.closeFocus(outcome: outcome, intention: snapshot.focusIntention, detourCategory: "", detourDescription: "")
    }
```

- [ ] **Step 9: Run the session tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`

Expected: PASS.

- [ ] **Step 10: Verify the native build**

Run: `./gradlew :core:runMacNative`

Expected: `** BUILD SUCCEEDED **`. (The app launches; quit it — this step is build verification.)

- [ ] **Step 11: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr.

- [ ] **Step 12: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt \
        core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt \
        core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt \
        core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt \
        macos/DayView/TodayModel.swift
git commit -m "feat(core): expose the exit toll and the open detour to the native app

The closure bridge takes the intention and an optional detour at full arity,
a new action closes the running detour, and the snapshot carries whether
leaving now costs a name along with the open detour's motif and clock."
```

---

### Task 2: The closure sheet

**Files:**
- Create: `macos/DayView/FocusClosureSheet.swift`
- Delete: `macos/DayView/FocusClosureButtons.swift`
- Modify: `macos/DayView/TodayModel.swift`, `macos/DayView/RingView.swift`, `macos/DayView/MiniView.swift`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` (delete `stopFocus`)

**Interfaces:**
- Consumes from Task 1: `TodaySnapshot.earlyExitCostsName`, `DayViewSession.closeFocus(outcome:intention:detourCategory:detourDescription:)`.
- Produces, for Task 3: `TodayModel.closeFocus(_ outcome: String, intention: String, detourCategory: String, detourDescription: String)` (the shim is gone), and the `showClosureSheet` presentation pattern both windows now use.

**Context:** the sheet is the app's fourth (`DetourCaptureSheet`, `DetourListSheet`, `MiniView.intentionSheet` precede it). Match their shape: a `@Binding var isPresented`, local `@State` draft fields, Cancel/confirm in a trailing `HStack`, `.tint(palette.amber)` on the confirming button.

---

- [ ] **Step 1: Create the sheet**

Create `macos/DayView/FocusClosureSheet.swift`:

```swift
import SwiftUI
import DayViewKit

/// The closure ritual: name the work at the close rather than as a toll on starting.
/// Leaving before the term with anything but "Completed" costs a named detour — tapping
/// such an outcome unfolds the capture instead of closing, and only Confirm leaves.
struct FocusClosureSheet: View {
    @ObservedObject var model: TodayModel
    @Binding var isPresented: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var intention = ""
    @State private var pendingOutcome: String?
    @State private var motif = ""
    @State private var detail = ""
    @State private var seeded = false

    private static let outcomes = ["COMPLETED", "PROGRESSED", "TO_RESUME"]

    private func label(_ outcome: String) -> String {
        switch outcome {
        case "COMPLETED": return "Completed"
        case "PROGRESSED": return "Progressed"
        default: return "Resume later"
        }
    }

    private func tint(_ outcome: String, _ palette: DayViewPalette) -> Color {
        switch outcome {
        case "COMPLETED": return palette.mint
        case "PROGRESSED": return palette.amber
        default: return palette.muted
        }
    }

    /// "Completed" is always free; the other two are tolled exactly when the snapshot says so.
    private func costsName(_ outcome: String) -> Bool {
        outcome != "COMPLETED" && model.snapshot.earlyExitCostsName
    }

    private var motifIsBlank: Bool {
        motif.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            Text("Close this focus").font(.headline)
            Text("WHAT WAS IT?")
                .font(.caption2).bold().kerning(1).foregroundStyle(palette.muted)
            TextField("What are you focusing on?", text: $intention)
                .textFieldStyle(.roundedBorder)
            HStack(spacing: 8) {
                ForEach(Self.outcomes, id: \.self) { outcome in
                    Button(label(outcome)) {
                        if costsName(outcome) {
                            pendingOutcome = outcome
                        } else {
                            model.closeFocus(outcome, intention: intention, detourCategory: "", detourDescription: "")
                            isPresented = false
                        }
                    }
                    .buttonStyle(.bordered)
                    .tint(tint(outcome, palette))
                }
            }
            if let outcome = pendingOutcome {
                exitCapture(outcome: outcome, palette: palette)
            }
            HStack {
                Spacer()
                Button("Cancel") { isPresented = false }
            }
        }
        .padding(20)
        .frame(width: 380)
        .onAppear {
            // Seeded once, not per redraw: the field is a draft the user may edit while
            // ticks keep arriving.
            if !seeded {
                intention = model.snapshot.focusIntention
                seeded = true
            }
        }
        .onChange(of: model.snapshot.earlyExitCostsName) { _, costs in
            // The toll can lift under the user's feet: reaching the term mid-capture would
            // leave them naming a pull they no longer owe. The capture follows the toll,
            // not the tap that opened it.
            if !costs { pendingOutcome = nil }
        }
    }

    /// The exit toll: name the pull that takes you out, optionally describe it, then leave.
    private func exitCapture(outcome: String, palette: DayViewPalette) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("WHAT PULLS YOU AWAY?")
                .font(.caption2).bold().kerning(1).foregroundStyle(palette.amber)
            TextField("E.g. unexpected call", text: $motif)
                .textFieldStyle(.roundedBorder)
            if !model.snapshot.recentDetourCategories.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(model.snapshot.recentDetourCategories, id: \.self) { category in
                            Button(category) { motif = category }
                                .buttonStyle(.bordered)
                                .tint(palette.muted)
                                .lineLimit(1)
                        }
                    }
                }
            }
            TextField("Optional detail", text: $detail)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Confirm") {
                    model.closeFocus(outcome, intention: intention, detourCategory: motif, detourDescription: detail)
                    isPresented = false
                }
                .keyboardShortcut(.defaultAction)
                .tint(palette.amber)
                // The controller refuses a blank motif silently; never offer that path.
                .disabled(motifIsBlank)
            }
        }
    }
}
```

- [ ] **Step 2: Replace the model's shim with the real wrapper**

In `macos/DayView/TodayModel.swift`, replace the temporary shim added in Task 1:

```swift
    // Temporary shim: preserves today's behaviour (the controller's own default was the
    // persisted intention) until the closure sheet supplies the real values.
    func closeFocus(_ outcome: String) {
        session.closeFocus(outcome: outcome, intention: snapshot.focusIntention, detourCategory: "", detourDescription: "")
    }
```

with:

```swift
    func closeFocus(_ outcome: String, intention: String, detourCategory: String, detourDescription: String) {
        session.closeFocus(outcome: outcome, intention: intention, detourCategory: detourCategory, detourDescription: detourDescription)
    }
```

In the same file, delete this line entirely:

```swift
    func stopFocus() { session.stopFocus() }
```

- [ ] **Step 3: Delete the superseded buttons view**

```bash
git rm macos/DayView/FocusClosureButtons.swift
```

- [ ] **Step 4: Route the main window through the sheet**

In `macos/DayView/RingView.swift`:

Add the presentation state next to the other `@State` flags (after `@State private var showDetourList = false`):

```swift
    @State private var showClosureSheet = false
```

Register the sheet next to the existing two, after the `showDetourList` line:

```swift
        .sheet(isPresented: $showClosureSheet) { FocusClosureSheet(model: model, isPresented: $showClosureSheet) }
```

In `focusSection`, replace the whole status switch's non-IDLE branches. Replace:

```swift
                case "BREAK", "OVERTIME":
                    // Task 10/11/12 gives this its real behaviour
                    // Relaunch the next session of the sequence, keeping the intention.
                    Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                        .tint(palette.amber)
                    Button("Stop focus") { model.stopFocus() }
                        .tint(palette.red)
                default: // "ACTIVE"
                    Button("Stop focus") { model.stopFocus() }
                        .tint(palette.red)
                }
```

with:

```swift
                case "BREAK", "OVERTIME":
                    // Task 10/11/12 gives this its real behaviour
                    // Relaunch the next session of the sequence, keeping the intention.
                    Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                        .tint(palette.amber)
                    Button("Stop focus") { showClosureSheet = true }
                        .tint(palette.red)
                default: // "ACTIVE"
                    Button("Stop focus") { showClosureSheet = true }
                        .tint(palette.red)
                }
```

Delete the inline closure section — the sheet is now the one closure surface. Remove these lines from `focusSection`:

```swift
            // Task 10/11/12 gives this its real behaviour
            if model.snapshot.pomodoroStatus == "BREAK" || model.snapshot.pomodoroStatus == "OVERTIME" {
                closureSection
            }
```

and delete the `closureSection` property itself:

```swift
    // The closure ritual: name how the sequence ends so the session record and
    // clean-session ledger stay honest. Break-only; Stop stays an outcome-less abort.
    private var closureSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Close this focus")
                .font(.caption)
                .foregroundStyle(.secondary)
            FocusClosureButtons(model: model)
        }
    }
```

In `resumeRitual`, replace:

```swift
                Button("Stop") { model.stopFocus(); model.dismissResumeRitual() }
```

with:

```swift
                Button("Stop") { model.dismissResumeRitual(); showClosureSheet = true }
```

- [ ] **Step 5: Route the mini window through the sheet**

In `macos/DayView/MiniView.swift`, add the presentation state next to the existing `@State` flags:

```swift
    @State private var showClosureSheet = false
```

Attach the sheet to the same view the intention sheet is attached to (find the existing `.sheet(isPresented: $showIntentionSheet)` modifier and add this line directly after it):

```swift
        .sheet(isPresented: $showClosureSheet) { FocusClosureSheet(model: model, isPresented: $showClosureSheet) }
```

In `focusCard`, replace both Stop buttons. The `"ACTIVE"` branch's:

```swift
                    Button("Stop") { model.stopFocus() }
                        .tint(palette.red)
```

and the `"BREAK", "OVERTIME"` branch's identical pair, both become:

```swift
                    Button("Stop") { showClosureSheet = true }
                        .tint(palette.red)
```

Delete the closure-buttons row from the `"BREAK", "OVERTIME"` branch:

```swift
                FocusClosureButtons(model: model)
```

- [ ] **Step 6: Delete the outcome-less stop from the bridge**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, delete the whole function and its KDoc:

```kotlin
    /**
     * The native window's Stop, still a bridge rather than the closure ritual: it picks
     * TO_RESUME and names no detour, so before the term the controller silently refuses it
     * and the button does nothing; past the term it closes normally. Giving the native
     * window the real closure sheet — outcomes, the intention, the detour capture — is a
     * known, accepted gap, tracked with the rest of the native parity work.
     */
    fun stopFocus() = controller.closePomodoro(FocusClosureOutcome.TO_RESUME)
```

- [ ] **Step 7: Verify the native build**

Run: `./gradlew :core:runMacNative`

Expected: `** BUILD SUCCEEDED **`. A Swift compile error naming `stopFocus` or `FocusClosureButtons` means a call site was missed — there were five and two respectively.

- [ ] **Step 8: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr.

- [ ] **Step 9: Commit**

```bash
git add -A macos/DayView core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt
git commit -m "feat(macos): route every stop through the closure ritual

Stop opens a closure sheet in both windows: the intention, the three outcomes,
and — when leaving before the term costs a name — the detour capture that pays
it, with Confirm held back until a motif is given. The outcome-less stop is
gone; before the term it silently did nothing."
```

---

### Task 3: The open-detour banner

**Files:**
- Create: `macos/DayView/OpenDetourBanner.swift`
- Modify: `macos/DayView/TodayModel.swift`, `macos/DayView/RingView.swift`, `macos/DayView/MiniView.swift`
- Modify: `docs/superpowers/macos-native-parity-checklist.md`

**Interfaces:**
- Consumes from Task 1: `TodaySnapshot.detourOpenRunning` / `.detourOpenCategory` / `.detourOpenDescription` / `.detourOpenClock`, `DayViewSession.stopOpenDetour()`.
- Produces: nothing for later tasks — this is the last task.

**Why it replaces the focus panel rather than sitting beside it:** in the core the two are mutually exclusive (`startOpenDetour` clears `breakStart`, and a closure that hands off to a detour opens no break), and the Compose app renders them the same either/or way.

---

- [ ] **Step 1: Create the banner**

Create `macos/DayView/OpenDetourBanner.swift`:

```swift
import SwiftUI
import DayViewKit

/// A detour running on the stopwatch — opened by the exit toll when a focus is left early.
/// Mirrors the Compose OpenDetourPanel: the motif, an optional detail, the elapsed clock,
/// and the stop that commits the episode.
struct OpenDetourBanner: View {
    @ObservedObject var model: TodayModel

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("DETOUR")
                    .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.amber)
                Spacer()
                Text("RUNNING")
                    .font(.caption2).bold().kerning(0.7).foregroundStyle(palette.mint)
            }
            Text(model.snapshot.detourOpenCategory)
                .font(.body).foregroundStyle(palette.cloud)
            if !model.snapshot.detourOpenDescription.isEmpty {
                Text(model.snapshot.detourOpenDescription)
                    .font(.caption).foregroundStyle(palette.muted)
            }
            HStack {
                Text(model.snapshot.detourOpenClock)
                    .font(.system(size: 30, weight: .light, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(palette.cloud)
                Spacer()
                Button("Stop") { model.stopOpenDetour() }
                    .buttonStyle(.bordered)
                    .tint(palette.red)
            }
        }
        .dayViewPanel(palette)
    }
}
```

- [ ] **Step 2: Add the model wrapper**

In `macos/DayView/TodayModel.swift`, add next to the other detour wrappers:

```swift
    func stopOpenDetour() { session.stopOpenDetour() }
```

- [ ] **Step 3: Show it in the main window**

In `macos/DayView/RingView.swift`, in `body`'s `VStack`, replace:

```swift
                ringSection
                detourSection
                focusSection
                goalSection
```

with:

```swift
                ringSection
                detourSection
                // A running detour and a focus are mutually exclusive in the core, so the
                // panel that is live replaces the other rather than stacking with it.
                if model.snapshot.detourOpenRunning {
                    OpenDetourBanner(model: model)
                } else {
                    focusSection
                }
                goalSection
```

- [ ] **Step 4: Show it in the mini window**

In `macos/DayView/MiniView.swift`, wrap the body's `focusCard` reference the same way. Replace:

```swift
                    // Height-gated like the JVM mini (showGoalInMiniWindow: 400 at font scale 1).
                    if proxy.size.height >= 400 {
                        goalCard
                    }
                    focusCard
                }
```

with:

```swift
                    // Height-gated like the JVM mini (showGoalInMiniWindow: 400 at font scale 1).
                    if proxy.size.height >= 400 {
                        goalCard
                    }
                    if model.snapshot.detourOpenRunning {
                        OpenDetourBanner(model: model)
                    } else {
                        focusCard
                    }
                }
```

The goal card's height gate is untouched — only the `focusCard` reference is wrapped.

- [ ] **Step 5: Verify the native build**

Run: `./gradlew :core:runMacNative`

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 6: Update the parity checklist**

In `docs/superpowers/macos-native-parity-checklist.md`:

1. Delete this row from the Focus table:

```markdown
| Closure sheet with intention field + exit-toll detour capture in native | **PORT** | `FocusClosureButtons` calls `TodayModel.closeFocus(_ outcome:)`, which forwards to `DayViewSession.closeFocus(outcome:)` with no intention or detour text; `TodayModel.stopFocus()` likewise calls `closePomodoro(TO_RESUME)` with no detour category. Since `:core`'s `closePomodoro` silently no-ops an early exit without a named detour (`earlyExitRequiresDetour`), native's Stop button currently does nothing before the term is reached |
```

2. Add a row at the end of the "Done" table:

```markdown
| Focus exit: closure sheet (intention + three outcomes), exit-toll detour capture, open-detour banner in both windows | 12a |
```

3. Add this line to the Sync section's notes, as a new paragraph after the table:

```markdown
**New (from phase 12a):** `stopOpenDetour` refuses a blank motif and leaves the detour open. Natively that cannot happen today — only the exit toll opens a detour and it always names one — but a motif-less detour started on Android or the JVM app will sync over once sync lands, and the native Stop would then silently do nothing. The sync phase must add stop-time motif collection.
```

- [ ] **Step 7: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr.

- [ ] **Step 8: Commit**

```bash
git add macos/DayView docs/superpowers/macos-native-parity-checklist.md
git commit -m "feat(macos): show and stop the detour opened by an early exit

A running detour takes the focus panel's place in both windows, showing its
motif, its detail and a live clock, with a stop that commits the episode."
```

- [ ] **Step 9: Manual smoke test (run by the maintainer)**

`macosMain` and the Swift app have no automated coverage; this is the only end-to-end check.

1. Start a focus, press **Stop** well before the term → the closure sheet opens.
2. **Completed** → closes immediately, no detour.
3. Start again, Stop, tap **Progressed** → the capture unfolds; Confirm stays disabled until a motif is typed; confirming closes the session **and** the open-detour banner appears with a running clock in place of the focus panel.
4. The banner's **Stop** commits the detour — it appears in the day's detour tally on the ring and in the total.
5. Repeat steps 1–4 in the **mini** window.
6. Start a short session (set the duration to its minimum), Stop, tap **Progressed**, and let the term pass while the capture is open → the capture collapses back to the outcome row on its own.

---

## Notes for the reviewer

- **`earlyExitCostsName` is one boolean by design, not by simplification.** `earlyExitRequiresDetour`'s only outcome-dependence is `outcome != COMPLETED`, so `COMPLETED` is always free and `PROGRESSED`/`TO_RESUME` always share a verdict. Passing `PROGRESSED` as the representative outcome is exact.
- **The disabled Confirm and the Kotlin test assert the same rule from both sides.** The controller refuses a blank motif silently — the exact failure mode this phase exists to remove — so the sheet must never offer that path, and `earlyProgressedWithoutAMotifChangesNothing` pins what the controller does if it ever is offered.
- **Task 1 deliberately leaves a shim.** Deleting `stopFocus` in Task 1 would break the native build until Task 2 lands; the one-argument `TodayModel.closeFocus` shim keeps every intermediate commit buildable and is removed in Task 2, Step 2.
- **The collapse uses `.onChange` without a paired `.onAppear`, and the spec's wording is stricter than the code needs.** Phase 10b's lesson — SwiftUI's `onChange` never fires for an initial value, so pair it with `onAppear` whenever a view can be *created* already in the triggering state — applies to views that must *act* on that initial state. Here the triggering state is "the toll has lifted", and the only thing the handler does is clear `pendingOutcome`, which SwiftUI has already reset to `nil` because a sheet's `@State` is fresh on each presentation. A presentation that starts with no toll therefore needs no correction: `costsName()` returns false and the outcome buttons close directly. Adding `.onAppear { if !costs { pendingOutcome = nil } }` would be a no-op guarding a state that cannot exist.
