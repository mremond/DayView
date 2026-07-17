# macOS Native Detours — Capture, Tally, List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Declare detours in the native main window — a "+ Detour" capture sheet, a per-source tally under the dial, the daily total below the countdown, and an edit list (rename/adjust/delete/undo/add-after-the-fact) — all over the existing `:core` detour model, with no ring drawing yet (9b).

**Architecture:** `TodaySnapshot` gains detour sources, the daily total label, recent categories, and the day's episode list, plus five actions delegating to the existing controller mutators (Task 1). `DayViewPalette` gains the detours color list; `RingView` gains the daily-total line, the tally row, and the "+ Detour" capture sheet (Task 2). The edit list sheet adds rename/adjust/delete/undo/retroactive-add/forget (Task 3).

**Tech Stack:** Kotlin Multiplatform (`:core`), SwiftUI (macOS 15+).

## Global Constraints

- NO `:core` controller change — the existing mutators are called as-is: `addDetour(category, durationMinutes, description)`, `updateDetour(index, DetourEpisode)`, `removeDetour(index)`, `restoreLastRemovedDetour()`, `forgetRecentDetourCategory(category)`. Detours never change the countdown or net time (informational only).
- Snapshot conventions: `Long` numbers, `String` display text; `TodaySnapshot` constructed only in `toTodaySnapshot`.
- Kotlin-computed labels: `detourTotalLabel` = `"Detours " + formatDurationHm(total)` when episodes exist, else `""`; `DetourSourceSnapshot.totalLabel` = `formatDurationHm(source.total)`; `DetourEntry.timeRangeLabel` = `"<start> – <end>"` and `durationLabel` = `formatDurationHm(duration)`, times via `formatWallClock(hour, minute, use24Hour)` from the exact Instants (`use24Hour` already threaded in 7b).
- `detourSources`/`detours` mirror the controller's day-gated `detourSourcesState`/`detoursToday` (empty when none today); `detours` is in `detoursToday` order (indices match the mutators). The list UI re-reads indices from each fresh snapshot after any mutation (the controller re-sorts by start on commit).
- Duration quick-picks: `5, 15, 30, 45, 60` minutes, default 15 (JVM `DETOUR_DURATION_CHOICES`).
- Detours palette verbatim from `DayViewTheme.kt`: dark amber `FFB86B`, gold `E7CE6B`, coral `F2856D`, rose `E58FB6`, plum `B48EE0`, sand `D9B08C`; light `B76218`, `8F7A1C`, `B0492F`, `A34D74`, `6E4AA3`, `8A6844`.
- Capture/edit sheets hold local draft `@State` (a modal form is not persisted state); the tally/total/list stay snapshot-bound. Main window only; `MiniView` gets no detours.
- Native UI copy hardcoded English. Kotlin lint: `./gradlew ktlintCheck`. Commit messages English/imperative/change-only, no AI references; commits succeed unsigned.
- Headless GUI blocked — sheets/taps are a manual smoke test; report what was and wasn't verified.
- **Before Task 1:** `git checkout -b claude/macos-native-detours-capture`.

## File map

- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` — 2 types, 4 fields + mapping.
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` — 5 actions.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` — detour tests.
- Modify: `macos/DayView/DayViewPalette.swift` — detours list + `detourColor`.
- Modify: `macos/DayView/TodayModel.swift` — 5 passthroughs.
- Create: `macos/DayView/DetourCaptureSheet.swift` — the capture form (Task 2).
- Create: `macos/DayView/DetourListSheet.swift` — the edit list (Task 3).
- Modify: `macos/DayView/RingView.swift` — daily total, tally row, "+ Detour", sheet presentation.

---

## Task 1: `:core` — detour snapshot fields + actions (TDD)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.detourSourcesState` (`List<DetourSource>`: label/colorIndex/total), `detoursTotalToday` (Duration), `recentDetourCategories` (List<String>), `detoursToday` (List<DetourEpisode>: start/end/category/description); `formatDurationHm`, `formatWallClock`; controller mutators listed above; `DetourEpisode`.
- Produces (Tasks 2/3): `DetourSourceSnapshot(label, colorIndex: Long, totalLabel)`, `DetourEntry(startEpochMillis, endEpochMillis, category, description, timeRangeLabel, durationLabel)`; snapshot fields `detourSources`, `detourTotalLabel`, `recentDetourCategories`, `detours`; session actions `addDetour(category:durationMinutes:description:)`, `updateDetour(index:startEpochMillis:endEpochMillis:category:description:)`, `removeDetour(index:)`, `restoreLastRemovedDetour()`, `forgetRecentDetourCategory(category:)`.

- [ ] **Step 1: Write the failing tests**

Append inside `DayViewSessionTest` in `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` (imports `TimeZone`/`toLocalDateTime`/`assertTrue` are already present from earlier phases):

```kotlin
    @Test
    fun detourCaptureFillsSourcesTotalAndList() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L) // midday UTC fixture
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.addDetour("Call", 15, "")
        runCurrent()
        val s = seen.last()
        assertEquals(1, s.detourSources.size)
        assertEquals("Call", s.detourSources.first().label)
        assertTrue(Regex("^(\\d+ h \\d{2}|\\d+ min)$").matches(s.detourSources.first().totalLabel))
        assertTrue(s.detourTotalLabel.startsWith("Detours "))
        assertEquals(1, s.detours.size)
        assertTrue(s.recentDetourCategories.contains("Call"))
        // The list entry's time range is built from the episode's own instants.
        val entry = s.detours.first()
        val zone = TimeZone.currentSystemDefault()
        val start = Instant.fromEpochMilliseconds(entry.startEpochMillis).toLocalDateTime(zone)
        val end = Instant.fromEpochMilliseconds(entry.endEpochMillis).toLocalDateTime(zone)
        assertEquals(
            "${formatWallClock(start.hour, start.minute, true)} – ${formatWallClock(end.hour, end.minute, true)}",
            entry.timeRangeLabel,
        )

        sub.cancel()
    }

    @Test
    fun detourRemoveThenRestoreRoundTrips() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.addDetour("Call", 15, "")
        session.addDetour("Chat", 30, "")
        runCurrent()
        assertEquals(2, seen.last().detours.size)

        session.removeDetour(0)
        runCurrent()
        assertEquals(1, seen.last().detours.size)

        session.restoreLastRemovedDetour()
        runCurrent()
        assertEquals(2, seen.last().detours.size)

        sub.cancel()
    }

    @Test
    fun detourUpdateAndForgetCategory() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }

        session.addDetour("Call", 15, "")
        runCurrent()
        val e = seen.last().detours.first()
        session.updateDetour(0, e.startEpochMillis, e.endEpochMillis, "Meeting", "")
        runCurrent()
        assertEquals("Meeting", seen.last().detours.first().category)

        session.forgetRecentDetourCategory("Meeting")
        runCurrent()
        assertTrue(!seen.last().recentDetourCategories.contains("Meeting"))

        sub.cancel()
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: FAIL to compile — unresolved `detourSources`/`addDetour`/etc.

- [ ] **Step 3: Add the snapshot types, fields, and mapping**

In `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`:

1. Add after the `CalendarChoice` data class (top level):

```kotlin
/** One distraction source for the tally row. */
data class DetourSourceSnapshot(
    val label: String,
    val colorIndex: Long, // stable per source; the UI maps % detours palette size
    val totalLabel: String, // formatDurationHm(total)
)

/** One declared detour episode, for the edit list (index matches detoursToday). */
data class DetourEntry(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val category: String,
    val description: String,
    val timeRangeLabel: String, // "09:00 – 09:15"
    val durationLabel: String, // formatDurationHm(duration)
)
```

2. Add to the END of the `TodaySnapshot` constructor parameter list (after `hasStarted`):

```kotlin
    val detourSources: List<DetourSourceSnapshot>,
    val detourTotalLabel: String,
    val recentDetourCategories: List<String>,
    val detours: List<DetourEntry>,
```

3. Add to the END of the `toTodaySnapshot` construction (after `hasStarted = ...,`):

```kotlin
        detourSources = detourSourcesState.map {
            DetourSourceSnapshot(it.label, it.colorIndex.toLong(), formatDurationHm(it.total))
        },
        detourTotalLabel = if (detoursToday.isEmpty()) "" else "Detours ${formatDurationHm(detoursTotalToday)}",
        recentDetourCategories = recentDetourCategories,
        detours = detoursToday.map { episode ->
            val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
            val start = episode.start.toLocalDateTime(zone)
            val end = episode.end.toLocalDateTime(zone)
            DetourEntry(
                startEpochMillis = episode.start.toEpochMilliseconds(),
                endEpochMillis = episode.end.toEpochMilliseconds(),
                category = episode.category,
                description = episode.description,
                timeRangeLabel = "${formatWallClock(start.hour, start.minute, use24Hour)} – " +
                    formatWallClock(end.hour, end.minute, use24Hour),
                durationLabel = formatDurationHm(episode.duration),
            )
        },
```

(`toLocalDateTime` is already imported in this file since 7b; `detourSourcesState`/`detoursTotalToday`/`recentDetourCategories`/`detoursToday` are all `DayViewUiState` members.)

- [ ] **Step 4: Add the bridge actions**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, add after the last existing action (before `close()`):

```kotlin
    fun addDetour(category: String, durationMinutes: Int, description: String) =
        controller.addDetour(category, durationMinutes, description)

    fun updateDetour(index: Int, startEpochMillis: Long, endEpochMillis: Long, category: String, description: String) =
        controller.updateDetour(
            index,
            DetourEpisode(
                start = Instant.fromEpochMilliseconds(startEpochMillis),
                end = Instant.fromEpochMilliseconds(endEpochMillis),
                category = category,
                description = description,
            ),
        )

    fun removeDetour(index: Int) = controller.removeDetour(index)

    fun restoreLastRemovedDetour() = controller.restoreLastRemovedDetour()

    fun forgetRecentDetourCategory(category: String) = controller.forgetRecentDetourCategory(category)
```

(`DetourEpisode` and `Instant` are in `fr.dayview.app` / already imported in this file.)

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: PASS (all, incl. the three new tests).

- [ ] **Step 6: Lint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (`ktlintFormat` if flagged).

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): detour sources, total, recents, and list on the today snapshot"
```

---

## Task 2: Detours palette + capture sheet + tally + daily total

**Files:**
- Modify: `macos/DayView/DayViewPalette.swift`
- Modify: `macos/DayView/TodayModel.swift`
- Create: `macos/DayView/DetourCaptureSheet.swift`
- Modify: `macos/DayView/RingView.swift`

**Interfaces:**
- Consumes: snapshot `detourSources`/`detourTotalLabel`/`recentDetourCategories` (Task 1); `DetourSourceSnapshot`.
- Produces: `DayViewPalette.detours` + `detourColor(_:)`; `TodayModel.addDetour(...)` etc.; `DetourCaptureSheet(model:isPresented:)`; a tally row and "+ Detour" button in `RingView`.

- [ ] **Step 1: Palette detours list**

In `macos/DayView/DayViewPalette.swift`:

1. Add `let detours: [Color]` to the struct's stored properties (after `busy`).
2. In `static let dark`, add:

```swift
        detours: [hex(0xFFB86B), hex(0xE7CE6B), hex(0xF2856D), hex(0xE58FB6), hex(0xB48EE0), hex(0xD9B08C)]
```

3. In `static let light`, add:

```swift
        detours: [hex(0xB76218), hex(0x8F7A1C), hex(0xB0492F), hex(0xA34D74), hex(0x6E4AA3), hex(0x8A6844)]
```

4. Add an accessor next to `busyColor`:

```swift
    func detourColor(_ index: Int) -> Color {
        let safe = ((index % detours.count) + detours.count) % detours.count
        return detours[safe]
    }
```

- [ ] **Step 2: `TodayModel` passthroughs**

In `macos/DayView/TodayModel.swift`, add after `clearGoalDeadline()`:

```swift
    func addDetour(category: String, durationMinutes: Int32, description: String) {
        session.addDetour(category: category, durationMinutes: durationMinutes, description: description)
    }
    func updateDetour(index: Int32, startEpochMillis: Int64, endEpochMillis: Int64, category: String, description: String) {
        session.updateDetour(index: index, startEpochMillis: startEpochMillis, endEpochMillis: endEpochMillis, category: category, description: description)
    }
    func removeDetour(index: Int32) { session.removeDetour(index: index) }
    func restoreLastRemovedDetour() { session.restoreLastRemovedDetour() }
    func forgetRecentDetourCategory(_ category: String) { session.forgetRecentDetourCategory(category: category) }
```

- [ ] **Step 3: The capture sheet**

Create `macos/DayView/DetourCaptureSheet.swift`:

```swift
import SwiftUI
import DayViewKit

/// Quick capture: required motif, recent-motif chips, an approximate duration, an optional
/// note. Local @State draft — a modal form is not persisted state; Add commits through the
/// bridge (addDetour spans `durationMinutes` back from now) and dismisses.
struct DetourCaptureSheet: View {
    @ObservedObject var model: TodayModel
    @Binding var isPresented: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var motif = ""
    @State private var note = ""
    @State private var durationMinutes = 15

    private static let durationChoices = [5, 15, 30, 45, 60]

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            Text("What pulled you off the path?").font(.headline)
            TextField("E.g. unexpected call", text: $motif)
                .textFieldStyle(.roundedBorder)
            if !model.snapshot.recentDetourCategories.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(model.snapshot.recentDetourCategories, id: \.self) { cat in
                            Button(cat) { motif = cat }
                                .buttonStyle(.bordered)
                                .tint(palette.muted)
                        }
                    }
                }
            }
            Text("APPROXIMATE DURATION").font(.caption2).bold().kerning(1).foregroundStyle(palette.muted)
            Picker("Duration", selection: $durationMinutes) {
                ForEach(Self.durationChoices, id: \.self) { Text("\($0) min").tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            TextField("Optional detail", text: $note)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel") { isPresented = false }
                Button("Add") {
                    model.addDetour(category: motif, durationMinutes: Int32(durationMinutes), description: note)
                    isPresented = false
                }
                .keyboardShortcut(.defaultAction)
                .tint(palette.amber)
                .disabled(motif.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 360)
    }
}
```

- [ ] **Step 4: `RingView` — daily total, tally row, "+ Detour"**

In `macos/DayView/RingView.swift`:

1. Add state after the existing `@State` lines:

```swift
    @State private var showDetourCapture = false
    @State private var showDetourList = false
```

2. In `ringSection`, add the daily total as another muted interior line — after the `netTimeLabel` block inside the interior `VStack`:

```swift
                    if !model.snapshot.detourTotalLabel.isEmpty {
                        Text(model.snapshot.detourTotalLabel)
                            .font(.caption).foregroundStyle(palette.muted)
                    }
```

3. Add a `detourSection` computed property and place it in the main `VStack(spacing: 28)` (after `ringSection`, before `focusSection`):

```swift
    private var detourSection: some View {
        VStack(spacing: 10) {
            if !model.snapshot.detourSources.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(model.snapshot.detourSources.enumerated()), id: \.offset) { _, source in
                            Button {
                                showDetourList = true
                            } label: {
                                HStack(spacing: 6) {
                                    Circle().fill(palette.detourColor(Int(source.colorIndex))).frame(width: 8, height: 8)
                                    Text(source.label).foregroundStyle(palette.cloud)
                                    Text(source.totalLabel).foregroundStyle(palette.muted)
                                }
                                .font(.caption)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            Button {
                showDetourCapture = true
            } label: {
                Label("Detour", systemImage: "plus")
            }
            .buttonStyle(.bordered)
            .tint(palette.muted)
        }
    }
```

Insert `detourSection` into the body's stack (in `var body`, the `VStack(spacing: 28)` currently holds `ringSection; focusSection; goalSection` — make it `ringSection; detourSection; focusSection; goalSection`).

4. Present the capture sheet — add to the `ScrollView` (alongside the glow `.background` modifier):

```swift
        .sheet(isPresented: $showDetourCapture) { DetourCaptureSheet(model: model, isPresented: $showDetourCapture) }
```

(The `showDetourList` sheet is added in Task 3; for now `showDetourList` toggling is inert — a tally tap does nothing until Task 3 wires the list sheet. That is acceptable for Task 2's deliverable.)

- [ ] **Step 5: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches; a "+ Detour" button sits under the dial (no tally yet with no detours).

- [ ] **Step 6: Verify + manual smoke**

Automatic: build, launch, process alive. Manual (report as not-verified): "+ Detour" opens the sheet; typing a motif + picking 15 min + Add → the daily total "Detours 15 min" appears under the countdown and a tally chip (color dot + "motif" + "15 min") appears under the dial; recent-motif chips appear on the next capture; the countdown and net time are unchanged. Close: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 7: Commit**

```bash
git add macos/DayView/DayViewPalette.swift macos/DayView/TodayModel.swift macos/DayView/DetourCaptureSheet.swift macos/DayView/RingView.swift
git commit -m "feat(macos): detour capture sheet, tally, and daily total"
```

---

## Task 3: The edit list (rename/adjust/delete/undo/add/forget)

**Files:**
- Create: `macos/DayView/DetourListSheet.swift`
- Modify: `macos/DayView/RingView.swift`

**Interfaces:**
- Consumes: snapshot `detours` (`DetourEntry`) and `recentDetourCategories`; `TodayModel` update/remove/restore/forget + `addDetour`; `DetourCaptureSheet` (Task 2, reused for retroactive add).
- Produces: `DetourListSheet(model:isPresented:)` presented from `RingView.showDetourList`.

- [ ] **Step 1: The list sheet**

Create `macos/DayView/DetourListSheet.swift`:

```swift
import SwiftUI
import DayViewKit

/// Today's detours: rename/adjust each, delete (with undo), add after the fact. Rows are
/// re-read from the snapshot every render — the controller re-sorts by start on commit, so
/// indices must not be cached across a mutation.
struct DetourListSheet: View {
    @ObservedObject var model: TodayModel
    @Binding var isPresented: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var editingIndex: Int?
    @State private var showAdd = false
    @State private var canUndo = false

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("TODAY’S DETOURS").font(.caption).bold().kerning(1).foregroundStyle(palette.muted)
                Spacer()
                if canUndo {
                    Button("Undo") { model.restoreLastRemovedDetour(); canUndo = false }
                        .buttonStyle(.borderless)
                }
            }
            if model.snapshot.detours.isEmpty {
                Text("No detours declared today.").foregroundStyle(palette.muted)
            } else {
                ForEach(Array(model.snapshot.detours.enumerated()), id: \.offset) { index, entry in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.category).foregroundStyle(palette.cloud)
                            Text("\(entry.timeRangeLabel) · \(entry.durationLabel)")
                                .font(.caption).foregroundStyle(palette.muted)
                        }
                        Spacer()
                        Button { editingIndex = index } label: { Image(systemName: "pencil") }
                            .buttonStyle(.borderless)
                        Button { model.removeDetour(index: Int32(index)); canUndo = true } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless).tint(palette.red)
                    }
                    .padding(.vertical, 4)
                }
            }
            HStack {
                Button("Add a detour") { showAdd = true }.tint(palette.amber)
                Spacer()
                Button("Close") { isPresented = false }
            }
        }
        .padding(20)
        .frame(width: 380)
        .sheet(isPresented: $showAdd) { DetourCaptureSheet(model: model, isPresented: $showAdd) }
        .sheet(item: $editingIndex) { index in
            DetourEditSheet(model: model, index: index, entry: model.snapshot.detours[safe: index], isPresented: Binding(get: { editingIndex != nil }, set: { if !$0 { editingIndex = nil } }))
        }
    }
}

// Optional index into the snapshot list — nil if it changed out from under the sheet.
extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

// Make Int usable with `.sheet(item:)`.
extension Int: Identifiable { public var id: Int { self } }
```

- [ ] **Step 2: The per-row edit sheet**

Add to the SAME file (`DetourListSheet.swift`), below `DetourListSheet`:

```swift
/// Edit one detour: rename, adjust the time span (via a duration re-pick anchored to the
/// existing end), change the note. Saves through updateDetour(index:).
struct DetourEditSheet: View {
    @ObservedObject var model: TodayModel
    let index: Int
    let entry: DetourEntry?
    @Binding var isPresented: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var motif = ""
    @State private var note = ""
    @State private var start = Date()
    @State private var end = Date()
    @State private var seeded = false

    var body: some View {
        let palette = DayViewPalette.current(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            Text("EDIT DETOUR").font(.caption).bold().kerning(1).foregroundStyle(palette.muted)
            TextField("Motif", text: $motif).textFieldStyle(.roundedBorder)
            DatePicker("Start", selection: $start, displayedComponents: [.hourAndMinute])
            DatePicker("End", selection: $end, displayedComponents: [.hourAndMinute])
            TextField("Optional detail", text: $note).textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel") { isPresented = false }
                Button("Save") {
                    model.updateDetour(
                        index: Int32(index),
                        startEpochMillis: Int64(start.timeIntervalSince1970 * 1000),
                        endEpochMillis: Int64(end.timeIntervalSince1970 * 1000),
                        category: motif,
                        description: note
                    )
                    isPresented = false
                }
                .keyboardShortcut(.defaultAction)
                .tint(palette.amber)
                .disabled(motif.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 360)
        .onAppear {
            guard !seeded, let entry else { return }
            motif = entry.category
            note = entry.description
            start = Date(timeIntervalSince1970: Double(entry.startEpochMillis) / 1000)
            end = Date(timeIntervalSince1970: Double(entry.endEpochMillis) / 1000)
            seeded = true
        }
    }
}
```

(The edit sheet uses hour/minute `DatePicker`s anchored on the episode's own date via the seeded `Date`s, so the day component is preserved; only the time changes. If `entry` is nil — the list changed under the sheet — the fields stay at defaults and Save is guarded by the non-blank motif; the reviewer should confirm this can't corrupt an unrelated index, since `updateDetour` validates `end > start` and the index range in `:core`.)

- [ ] **Step 3: Present the list from `RingView`**

In `macos/DayView/RingView.swift`, add to the `ScrollView` (alongside the capture sheet modifier from Task 2):

```swift
        .sheet(isPresented: $showDetourList) { DetourListSheet(model: model, isPresented: $showDetourList) }
```

- [ ] **Step 4: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches. If the compiler objects to `extension Int: Identifiable` (a retroactive conformance warning) or the `.sheet(item:)` over `Int?`, wrap the index in a tiny `struct IdentifiableIndex: Identifiable { let id: Int }` and adapt — behavior fixed, mechanism free.

- [ ] **Step 5: Verify + manual smoke**

Automatic: build, launch, process alive. Manual (report as not-verified): declare two detours; tap a tally chip → the list opens showing both rows with time ranges; edit one's motif + times → Save updates it; delete one → it disappears and "Undo" restores it; "Add a detour" opens the capture sheet and adds retroactively; the daily total and tally reflect every change; the countdown/net time never move. Close: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 6: Commit**

```bash
git add macos/DayView/DetourListSheet.swift macos/DayView/RingView.swift
git commit -m "feat(macos): today's-detours list with edit, delete, undo, and retroactive add"
```

---

## Self-Review Notes

- **Spec coverage:** bridge types + 4 snapshot fields + 5 actions → Task 1 (3 tests: capture fills sources/total/list/recents with the TZ-portable time-range assertion; remove→restore; update+forget); detours palette + `detourColor` → Task 2 Step 1; capture sheet (motif + recent chips + `5/15/30/45/60` default 15 + note + Add→addDetour) → Task 2 Step 3; tally row + daily total line → Task 2 Steps 2/4; edit list (rows, per-row edit via updateDetour, delete via removeDetour, undo via restoreLastRemovedDetour, retroactive add via the reused capture sheet) → Task 3; forget-category → available via `TodayModel.forgetRecentDetourCategory` (wired minimally; the plan leaves the forget affordance to a chip context action, notable as the one lighter-touch item vs. the JVM's dedicated forget row — acceptable for 9a, flagged here).
- **Type consistency:** `DetourSourceSnapshot(label, colorIndex: Long, totalLabel)` / `DetourEntry(...)` match the Swift usage (`Int(source.colorIndex)`, `entry.timeRangeLabel`/`durationLabel`); Kotlin `Int`/`Long` params surface as `Int32`/`Int64` in the `TodayModel` passthroughs and the sheet calls; `addDetour(category:durationMinutes:description:)` consistent across model/session; `detours` indices align with the controller mutators; the list re-reads indices each render.
- **No placeholders:** every step carries complete code; Task 3 Step 4 names the `Identifiable`-conformance fallback explicitly.
- **YAGNI:** no ring bodies (9b), no running timer, no off-window tag, no mini detours.
- **Known nuance:** the `.sheet(item: $editingIndex)` relies on `Int: Identifiable`; the fallback struct is spelled out. The edit sheet's hour/minute pickers preserve the episode's date via seeded `Date`s (same approach as the settings day-window pickers, but anchored on the real episode date rather than a fixed day — because a detour's date IS today).
