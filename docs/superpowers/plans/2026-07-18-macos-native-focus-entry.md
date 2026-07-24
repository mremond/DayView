# macOS Native Focus Entry Implementation Plan (Phase 12b)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make starting a focus free in the native macOS app — no required intention, a one-tap 5-minute preset, a break that offers the same real choice as idle — and make overtime read as overtime rather than as a break.

**Architecture:** `:core`'s controller already implements free entry; only the Swift-facing facade and the SwiftUI views change. The bridge gains one action (`quickStartFocus`) and one Kotlin-computed label (`resumeRitualLine`). In Swift, `IDLE` and `BREAK` collapse onto one entry branch — removing the Relaunch button the shared design rejects — and `OVERTIME` gets its own branch reading Kotlin-computed labels instead of composing its own.

**Tech Stack:** Kotlin Multiplatform (`:core` commonMain / commonTest), Kotlin/Native macOS target, SwiftUI (macOS 15+), XcodeGen.

## Global Constraints

- **The controller does not change.** `startPomodoro` already dropped its blank-intention guard, clamps to 5–180 minutes, and snapshots `pomodoroSessionMinutes` so a preset never rewrites the preference. A task that edits `DayViewController.kt` has gone wrong.
- **No Kotlin default parameters on bridge functions.** Phase 7b established that default parameters change the exported Swift selector; bridge signatures are full-arity and explicit.
- **Presentation labels are computed in Kotlin**, once in `toTodaySnapshot`, never per Swift view. This phase exists partly *because* that rule was broken: `RingView` composes `"\(pomodoroClock) left to stay on track."`, and `pomodoroClock` means "+12 min" past the term.
- **The native UI is hardcoded English.** French localization is a separate cross-cutting phase; do not add `values-fr` entries.
- **Out of scope:** the last-closure chip (`FocusClosureChip`), the overtime closure reminder (needs the notification plumbing deferred in 10b), removing Stop from the resume ritual during overtime, and inline closure during overtime — the modal sheet from 12a stays the closure surface.
- ktlint is enforced (`./gradlew ktlintFormat` auto-fixes most findings). Full gate before each commit:
  `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
- Both builds stay green at every task: `./gradlew :core:runMacNative` must succeed at the end of each one.
- Commit messages: English, no reference to Claude/Anthropic/an AI assistant, no reference to `docs/superpowers/`, no test-plan or verification section.

## File Structure

| File | Responsibility |
|---|---|
| `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` (**modify**) | `quickStartFocus(intention:)` |
| `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` (**modify**) | `resumeRitualLine`, the ritual's sentence computed once |
| `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` (**modify**) | Quick start and free entry |
| `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt` (**modify**) | `resumeRitualLine` per status |
| `macos/DayView/TodayModel.swift` (**modify**) | `quickStartFocus` wrapper |
| `macos/DayView/RingView.swift` (**modify**) | Free entry, the 5-min preset, break-as-entry, the OVERTIME branch, the ritual line, the re-sync condition |
| `macos/DayView/MiniView.swift` (**modify**) | Free entry, the 5-min preset, break-as-entry, `focusLine` for the status row |
| `docs/superpowers/macos-native-parity-checklist.md` (**modify**, Task 3) | Move the closed rows to Done; record the goal-hours label finding |

---

### Task 1: The bridge — quick start and the ritual's line

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`, `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt`

**Interfaces:**
- Consumes (all pre-existing in `:core`): `DayViewController.startPomodoro(minutes)`, `DayViewController.setFocusIntention(intention)`, `formatOvertimeLabel(progress)`, `formatPomodoroClock(progress)`, `PomodoroStatus`.
- Produces, for Tasks 2 and 3:
  - `DayViewSession.quickStartFocus(intention: String)` — in Swift: `quickStartFocus(intention:)`
  - `TodaySnapshot.resumeRitualLine: String`

---

- [ ] **Step 1: Write the failing session tests**

Add to `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`, at the end of the class. The file already has a `TestScope.closureController(now)` helper (a `TestScope` extension, because `backgroundScope` is only reachable from one) — reuse it rather than building a controller inline.

```kotlin
    @Test
    fun quickStartRunsFiveMinutesWithoutRewritingThePreference() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = closureController(start)
        val session = DayViewSession(controller, backgroundScope)

        session.quickStartFocus("")
        runCurrent()

        val state = controller.stateFlow.value
        assertEquals(start + 5.minutes, state.pomodoroEnd, "the session runs five minutes")
        // The preference is the user's chosen default; a preset borrows a duration, it does
        // not change what Start will use next time.
        assertEquals(25, state.pomodoroMinutes, "the preferred duration is untouched")
    }

    @Test
    fun quickStartAppliesTheIntentionItIsGiven() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = closureController(start)
        val session = DayViewSession(controller, backgroundScope)

        session.quickStartFocus("Ship it")
        runCurrent()

        assertEquals("Ship it", controller.stateFlow.value.focusIntention)
    }

    @Test
    fun startingWithNoIntentionIsAllowed() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = closureController(start)
        val session = DayViewSession(controller, backgroundScope)

        // Entering focus is free: the blank-intention guard is gone from the controller, and
        // this is the bridge-level proof the native UI is finally free to honour it.
        session.startFocus("")
        runCurrent()

        assertEquals("ACTIVE", controller.stateFlow.value.toTodaySnapshot().pomodoroStatus)
    }
```

- [ ] **Step 2: Run the session tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`

Expected: compilation failure — `Unresolved reference: quickStartFocus`.

- [ ] **Step 3: Add the quick-start action**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, add directly after the existing `startFocus` function:

```kotlin
    /**
     * The one-tap preset: a five-minute session, whatever the preferred duration is. The
     * controller snapshots the session's own length, so the preference is never rewritten.
     * Takes the intention for the same reason [startFocus] does — the native field is Swift
     * local state, so a draft typed but not submitted would otherwise be lost. A blank
     * intention is legitimate: entering focus is free.
     */
    fun quickStartFocus(intention: String) {
        controller.setFocusIntention(intention)
        controller.startPomodoro(minutes = 5)
    }
```

- [ ] **Step 4: Run the session tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`

Expected: PASS.

- [ ] **Step 5: Write the failing snapshot tests**

Add to `core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt`, reusing the file's existing `controllerWith(snapshot, nowMillis)` helper.

```kotlin
    @Test
    fun resumeRitualLineReadsTheTimeLeftWhileActive() {
        val nowMillis = 1_699_956_000_000L
        val start = Instant.fromEpochMilliseconds(nowMillis)
        val controller = controllerWith(
            DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            nowMillis,
        )
        controller.startPomodoro()
        controller.tick(start + 5.minutes)

        assertEquals(
            "20:00 left to stay on track.",
            controller.stateFlow.value.toTodaySnapshot().resumeRitualLine,
        )
    }

    @Test
    fun resumeRitualLinePastTheTermSpeaksOfOvertimeNotTimeLeft() {
        val nowMillis = 1_699_956_000_000L
        val start = Instant.fromEpochMilliseconds(nowMillis)
        val controller = controllerWith(
            DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439, pomodoroMinutes = 25),
            nowMillis,
        )
        controller.startPomodoro()
        controller.tick(start + 37.minutes)

        // The bug this replaces: Swift composed "<clock> left to stay on track." from
        // pomodoroClock, which past the term is "+12 min" — "+12 min left to stay on track."
        assertEquals(
            "+12 min past the term — closing stays a choice.",
            controller.stateFlow.value.toTodaySnapshot().resumeRitualLine,
        )
    }

    @Test
    fun resumeRitualLineIsEmptyWithNoOpenSession() {
        val nowMillis = 1_699_956_000_000L
        val controller = controllerWith(
            DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            nowMillis,
        )
        assertEquals("", controller.stateFlow.value.toTodaySnapshot().resumeRitualLine)
    }
```

- [ ] **Step 6: Run the snapshot tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.TodaySnapshotTest'`

Expected: compilation failure — `Unresolved reference: resumeRitualLine`.

- [ ] **Step 7: Add the snapshot field**

In `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`, add to the `TodaySnapshot` constructor immediately after `val menuBarTitle: String,`:

```kotlin
    // The resume ritual's second line. Computed here rather than in Swift because the
    // sentence depends on which side of the term the session is on — and `pomodoroClock`
    // silently changes meaning at that boundary, which is how the Swift-composed version
    // came to read "+12 min left to stay on track."
    val resumeRitualLine: String,
```

Then in `toTodaySnapshot`, add immediately after `menuBarTitle = ...`:

```kotlin
        resumeRitualLine = when (pomodoro.status) {
            PomodoroStatus.ACTIVE -> "${formatPomodoroClock(pomodoro)} left to stay on track."
            PomodoroStatus.OVERTIME -> "${formatOvertimeLabel(pomodoro)} past the term — closing stays a choice."
            PomodoroStatus.BREAK, PomodoroStatus.IDLE -> ""
        },
```

Both sentences mirror Compose's `focus_resume_time_left` and `focus_resume_overtime` strings.

- [ ] **Step 8: Run the snapshot tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.TodaySnapshotTest'`

Expected: PASS.

- [ ] **Step 9: Verify the native build**

Run: `./gradlew :core:runMacNative`

Expected: `** BUILD SUCCEEDED **`. (It launches the app; quit it — this is build verification.)

- [ ] **Step 10: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr.

- [ ] **Step 11: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt \
        core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt \
        core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt \
        core/src/commonTest/kotlin/fr/dayview/app/TodaySnapshotTest.kt
git commit -m "feat(core): a five-minute preset and a resume line that knows the term

The preset borrows a duration for one session without rewriting the
preferred one. The resumption ritual's sentence is now computed with the
rest of the labels, so it can say what it means on both sides of the term."
```

---

### Task 2: The Swift entry — free to start, a break is an entry, overtime reads as overtime

**Files:**
- Modify: `macos/DayView/TodayModel.swift`, `macos/DayView/RingView.swift`, `macos/DayView/MiniView.swift`

**Interfaces:**
- Consumes from Task 1: `DayViewSession.quickStartFocus(intention:)`, `TodaySnapshot.resumeRitualLine`.
- Consumes (pre-existing): `TodaySnapshot.focusLine` — already Kotlin-computed and already correct for overtime (`"Focus · <intention> · +12 min"`), `TodaySnapshot.pomodoroClock`, `TodayModel.startFocus(intention:)`.
- Produces: nothing for later tasks.

**Context:** this task removes the `Relaunch` button. It is not a simplification — the shared design explicitly rejects "a single silent relaunch on the preferred duration" in favour of a real choice with an intention, a duration and the preset. `IDLE` and `BREAK` therefore render the same thing.

---

- [ ] **Step 1: Add the model wrapper**

In `macos/DayView/TodayModel.swift`, add directly after the existing `startFocus` line:

```swift
    func quickStartFocus(intention: String) { session.quickStartFocus(intention: intention) }
```

- [ ] **Step 2: Free the main window's entry and collapse the break into it**

In `macos/DayView/RingView.swift`, in `focusSection`, replace the whole `HStack` containing the stepper and the status switch. Replace:

```swift
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
                    .tint(palette.amber)
                case "BREAK", "OVERTIME":
                    // Relaunch only applies to BREAK (a break has no session left to close,
                    // so closePomodoro would be a no-op) and Stop only to OVERTIME (a running
                    // session can't be relaunched: startPomodoro returns early while one is
                    // active). Each button is dead in the state where the other is offered.
                    if model.snapshot.pomodoroStatus == "BREAK" {
                        // Relaunch the next session of the sequence, keeping the intention.
                        Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                            .tint(palette.amber)
                    }
                    if model.snapshot.pomodoroStatus == "OVERTIME" {
                        Button("Stop focus") { showClosureSheet = true }
                            .tint(palette.red)
                    }
                default: // "ACTIVE"
                    Button("Stop focus") { showClosureSheet = true }
                        .tint(palette.red)
                }
            }
```

with:

```swift
            HStack {
                Stepper("Duration: \(model.snapshot.pomodoroMinutes) min",
                        onIncrement: { model.changePomodoroDuration(5) },
                        onDecrement: { model.changePomodoroDuration(-5) })
                    // Adjustable whenever no session is open — a break included.
                    .disabled(sessionIsOpen)
                Spacer()
                switch model.snapshot.pomodoroStatus {
                case "ACTIVE":
                    Button("Stop focus") { showClosureSheet = true }
                        .tint(palette.red)
                case "OVERTIME":
                    // Past the term the session is still running and still counted; closing
                    // is the invitation, so the button is named for what it does.
                    Button("Close this focus") { showClosureSheet = true }
                        .tint(palette.red)
                default: // "IDLE" and "BREAK" — entering is free, and a break is an entry
                    Button("Start focus") {
                        model.startFocus(intention: intention)
                    }
                    .tint(palette.amber)
                    Button("5 min") {
                        model.quickStartFocus(intention: intention)
                    }
                    .buttonStyle(.bordered)
                    .tint(palette.muted)
                }
            }
```

Note there is no `.disabled(intention.isEmpty)` on Start any more — that removal *is* the free entry.

Add the `sessionIsOpen` helper next to the existing `palette` computed property near the top of the struct:

```swift
    private var sessionIsOpen: Bool {
        let status = model.snapshot.pomodoroStatus
        return status == "ACTIVE" || status == "OVERTIME"
    }
```

- [ ] **Step 3: Fix the intention re-sync, which the break change would otherwise break**

Still in `macos/DayView/RingView.swift`, in the `.onReceive(model.$snapshot)` block, replace:

```swift
            // A session just ended (closure or stop): the persisted intention is the
            // source of truth — Completed/Progressed cleared it, Resume later kept it —
            // so re-sync the field. Scoped to the non-IDLE -> IDLE transition so it
            // never clobbers mid-typing edits during normal ticks.
            if lastPomodoroStatus != "IDLE" && snap.pomodoroStatus == "IDLE" {
                intention = snap.focusIntention
            }
```

with:

```swift
            // A session just ended (closure or stop): the persisted intention is the
            // source of truth — Completed/Progressed cleared it, Resume later kept it —
            // so re-sync the field. Scoped to the transition out of an open session, not
            // to arriving at IDLE: a closure now lands on BREAK, and watching for IDLE
            // would leave the field holding the closed session's text. Still a transition,
            // so it never clobbers mid-typing edits during normal ticks.
            let wasOpen = lastPomodoroStatus == "ACTIVE" || lastPomodoroStatus == "OVERTIME"
            let isOpen = snap.pomodoroStatus == "ACTIVE" || snap.pomodoroStatus == "OVERTIME"
            if wasOpen && !isOpen {
                intention = snap.focusIntention
            }
```

- [ ] **Step 4: Free the mini window's entry and collapse its break**

In `macos/DayView/MiniView.swift`, in `focusCard`, replace the whole status switch. Replace:

```swift
            switch model.snapshot.pomodoroStatus {
            case "ACTIVE":
                HStack(spacing: 8) {
                    Text("Focus · \(model.snapshot.focusIntention)")
                        .lineLimit(1)
                    Spacer()
                    Text(model.snapshot.pomodoroClock).monospacedDigit()
                    Button("Stop") { showClosureSheet = true }
                        .tint(palette.red)
                }
            case "BREAK", "OVERTIME":
                HStack(spacing: 8) {
                    Text("Break · \(model.snapshot.focusIntention)")
                        .lineLimit(1)
                    Spacer()
                    Text(model.snapshot.pomodoroClock).monospacedDigit()
                    // Relaunch only applies to BREAK (a break has no session left to close,
                    // so closePomodoro would be a no-op) and Stop only to OVERTIME (a running
                    // session can't be relaunched: startPomodoro returns early while one is
                    // active). Each button is dead in the state where the other is offered.
                    if model.snapshot.pomodoroStatus == "BREAK" {
                        // Relaunch the next session of the sequence, keeping the intention.
                        Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                            .tint(palette.amber)
                    }
                    if model.snapshot.pomodoroStatus == "OVERTIME" {
                        Button("Stop") { showClosureSheet = true }
                            .tint(palette.red)
                    }
                }
            default: // "IDLE"
                Button("Start focus") {
                    draftIntention = model.snapshot.focusIntention
                    showIntentionSheet = true
                }
                .tint(palette.amber)
            }
```

with:

```swift
            switch model.snapshot.pomodoroStatus {
            case "ACTIVE", "OVERTIME":
                HStack(spacing: 8) {
                    // focusLine is computed in Kotlin and already says "+N min" past the
                    // term; composing the row here is what used to print "Break · …" for a
                    // session that was still running.
                    Text(model.snapshot.focusLine)
                        .lineLimit(1)
                    Spacer()
                    Button(model.snapshot.pomodoroStatus == "OVERTIME" ? "Close" : "Stop") {
                        showClosureSheet = true
                    }
                    .tint(palette.red)
                }
            default: // "IDLE" and "BREAK" — entering is free, and a break is an entry
                Button("Start focus") {
                    draftIntention = model.snapshot.focusIntention
                    showIntentionSheet = true
                }
                .tint(palette.amber)
            }
```

The separate clock `Text` is gone because `focusLine` already ends with the clock.

- [ ] **Step 5: Free the mini window's intention sheet and give it the preset**

Still in `macos/DayView/MiniView.swift`, in `intentionSheet`, replace:

```swift
            HStack {
                Spacer()
                Button("Cancel") { showIntentionSheet = false }
                Button("Start") {
                    model.startFocus(intention: draftIntention)
                    showIntentionSheet = false
                }
                .keyboardShortcut(.defaultAction)
                .disabled(draftIntention.isEmpty)
            }
```

with:

```swift
            HStack {
                Spacer()
                Button("Cancel") { showIntentionSheet = false }
                Button("5 min") {
                    model.quickStartFocus(intention: draftIntention)
                    showIntentionSheet = false
                }
                Button("Start") {
                    model.startFocus(intention: draftIntention)
                    showIntentionSheet = false
                }
                .keyboardShortcut(.defaultAction)
            }
```

- [ ] **Step 6: Correct the mini window's now-stale doc comment**

Still in `macos/DayView/MiniView.swift`, at the top of the file, replace:

```swift
/// Compact always-on-top companion: ring + countdown, a display-only goal card, and the
/// full focus card (intention sheet, live clock, stop/relaunch, closure ritual). Mirrors
/// the JVM mini window; observes the same TodayModel as the main window and menu bar.
```

with:

```swift
/// Compact always-on-top companion: ring + countdown, a display-only goal card, and the
/// full focus card (intention sheet, live clock, closure ritual). Mirrors the JVM mini
/// window; observes the same TodayModel as the main window and menu bar.
```

- [ ] **Step 7: Give the resume ritual the Kotlin-computed line**

In `macos/DayView/RingView.swift`, in `resumeRitual`, replace:

```swift
            if !model.snapshot.pomodoroClock.isEmpty {
                Text("\(model.snapshot.pomodoroClock) left to stay on track.")
                    .font(.caption).foregroundStyle(palette.muted)
            }
```

with:

```swift
            if !model.snapshot.resumeRitualLine.isEmpty {
                Text(model.snapshot.resumeRitualLine)
                    .font(.caption).foregroundStyle(palette.muted)
            }
```

- [ ] **Step 8: Verify the native build**

Run: `./gradlew :core:runMacNative`

Expected: `** BUILD SUCCEEDED **`. A Swift compile error mentioning `Relaunch` or an unused variable means a leftover from the replaced switches.

- [ ] **Step 9: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr.

- [ ] **Step 10: Commit**

```bash
git add macos/DayView
git commit -m "feat(macos): make entering a focus free, and let overtime read as overtime

Starting no longer demands an intention, and a five-minute preset sits
beside Start in both windows. A break now offers that same entry rather
than a one-tap relaunch on the preferred duration: resuming stays a real
choice. Past the term the panel offers to close rather than to stop, and
the mini window's row reads the shared focus line instead of composing a
break label for a session that is still running."
```

---

### Task 3: The parity checklist

**Files:**
- Modify: `docs/superpowers/macos-native-parity-checklist.md`

**Interfaces:** none — documentation only.

---

- [ ] **Step 1: Remove the four closed rows**

In `docs/superpowers/macos-native-parity-checklist.md`, delete these four rows from the Focus table (they are consecutive, immediately after the keyboard-shortcuts row):

```markdown
| `"OVERTIME"` pomodoroStatus value in the Swift status switch | **PORT** | `MiniView.focusCard` and `RingView.focusSection` (`// Task 10/11/12 gives this its real behaviour`) still fold `OVERTIME` into the `"BREAK"` case — same "Break" text and a "Relaunch" button appear while the session is still open, before it has ever been closed |
```

```markdown
| 5-min preset button | **PORT** | `RingView.focusSection`/`MiniView.intentionSheet` still require a non-empty intention before Start (`.disabled(intention.isEmpty)`) and start only at the stepper-set duration; the shared Compose UI's free entry plus one-tap 5-minute preset (56d7896) isn't ported |
```

```markdown
| Overtime `"+N min"` in MenuBarContent/MiniView | **PORT** | Partial gap: `focusLine`/`menuBarTitle` already read `"+N min"` for free via the Kotlin-computed snapshot (bf235ff), so `MenuBarContent`'s dropdown line and `RingView.focusSection`'s secondary line are already correct; `MiniView.focusCard`'s primary status row still hardcodes `"Break · …"` for OVERTIME (same gap as the status-switch item above) |
```

```markdown
| Resume-ritual copy during overtime | **PORT** | `RingView.swift:246` renders `"\(pomodoroClock) left to stay on track."`, and `pomodoroClock` is `"+12 min"` in OVERTIME — newly reachable now that the resume ritual is no longer gated to the pre-term phase, so it reads "+12 min left to stay on track." The Compose panel got a dedicated `focus_resume_overtime` string in both locales; native needs the equivalent conditional |
```

Leave the fifth row, "Break anchored on `breakStart`", in place: its remaining gap is the reminder scheduling, which needs notification and sound plumbing this phase does not build.

- [ ] **Step 2: Record what shipped**

Add a row at the end of the "Done" table:

```markdown
| Focus entry: free start, 5-min preset, a break offers the entry panel instead of a relaunch, overtime reads as overtime in the mini window and the resume ritual | 12b |
```

- [ ] **Step 3: Record the label finding this phase surfaced**

Add this paragraph immediately after the Focus table's existing "New (from the 10b review)" paragraph:

```markdown
**New (from phase 12b):** a sweep for Swift-composed labels — the class of defect behind the resume ritual's "+12 min left to stay on track." — found `goalHoursRemaining` worded three different ways in three surfaces (`MenuBarContent.swift`, `MiniView.swift`, `RingView.swift`: "Nh left" / "Nh left" / "Nh of working time left"). Nothing is wrong today, so it was not swept into 12b, but it is the same raw-number-crossing-the-bridge pattern and all three need translating separately. Fold it into the French-localization phase, which already revisits the Kotlin-computed labels.
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/macos-native-parity-checklist.md
git commit -m "docs: record the native focus entry against the parity checklist"
```

- [ ] **Step 5: Manual smoke test (run by the maintainer)**

The Swift half has no automated coverage; this is the only end-to-end check.

1. With the intention field **empty**, press **Start focus** — a session starts.
2. Press **5 min** — a five-minute session starts, and afterwards the duration stepper still shows the preferred duration, not 5.
3. Let a session run past its term → the panel shows `+N min` and a **Close this focus** button; the mini window's row reads `Focus · … · +N min`, not `Break · …`.
4. Close the session → the panel offers the full entry (intention, duration, Start, 5 min), **not** a Relaunch button; the duration stepper is adjustable; and the intention field reflects the outcome — cleared by Completed, kept by Resume later.
5. Quit and relaunch while a session is past its term → the resume ritual reads `+N min past the term — closing stays a choice.`

---

## Notes for the reviewer

- **Removing Relaunch is the point, not collateral.** The shared design rejects "a single silent relaunch on the preferred duration" in favour of a real choice; Compose has no such button, and phase 12a had made the native one *more* reliable by gating it to `BREAK`. `IDLE` and `BREAK` now render one branch.
- **Step 3 of Task 2 is a consequence, not a drive-by.** The intention re-sync watched for arrival at `IDLE`. Once a closure lands on `BREAK` instead, that transition stops firing and the field would keep the closed session's text — a silent bug introduced by an unrelated-looking change. The condition now watches for leaving an open session.
- **The mini window's `"Break · …"` is deleted rather than corrected.** Replacing the hand-composed row with `focusLine` removes the possibility of the bug, not just this instance of it. Same reasoning for `resumeRitualLine`.
- **`quickStartFocus` takes an intention** even though Compose's `quickStartPomodoro` takes none. Compose binds its field to controller state on every keystroke; the native field is Swift local state pushed through the bridge, so without the parameter a typed-but-unsubmitted draft would be silently dropped by the preset. Same reasoning as the existing `startFocus(intention:)`.
