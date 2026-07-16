# macOS Native Busy Arcs + Hover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw the calendar-busy lane on the native main ring (per-calendar colors, JVM geometry) with a hover label showing the event title(s) and exact times.

**Architecture:** `TodaySnapshot` gains `busyArcs: List<BusyArcSnapshot>` mapped from the controller's existing `busyBlockArcsState`, with a Kotlin-computed `hoverLabel` (exact Instants, `formatWallClock`, 12/24-h detected natively) and a pure wrap-aware `busyArcIndexAt` hit helper (Task 1). `DayRingCanvas` draws the lane and `RingView` adds `.onContinuousHover` with the Swift geometry half + a floating label (Task 2). Main window only — the JVM mini renders no busy arcs.

**Tech Stack:** Kotlin Multiplatform (`:core`), SwiftUI (macOS 15+).

## Global Constraints

- NO controller change — `busyBlockArcsState` consumed as-is (already net-time-gated and day-tagged; disabled ⇒ empty list, no extra gating).
- Hover label = `"<name> · <start>–<end>"` where name = non-blank titles joined ", " → else `calendarName` → else `"Busy"`; times from the arc's exact `start`/`end` Instants via the existing `formatWallClock(hour, minute, use24Hour)` — never derived from angles.
- 12/24-h: `toTodaySnapshot(use24Hour: Boolean = true)`; `DayViewSession` takes `use24Hour` as a constructor value (default `true` — existing tests stand); `DayViewNative` detects it once at creation via the `NSDateFormatter` "j"-template probe.
- Angle convention everywhere: −90° anchor (12 o'clock), clockwise — identical for `momentAngleDegrees`, the arcs, SwiftUI `Canvas`, and `atan2` in the y-down view space.
- Busy-lane geometry (JVM `DayViewTodayScreen.kt:1356`): lane radius = main radius − `lineWidth × 0.95`, stroke = `lineWidth × 0.7`, round caps, drawn after the track and before the remaining sweep. Drawing and hit-testing share ONE Swift source of geometry constants.
- Busy palette cribbed verbatim from `DayViewTheme.colors.busy` (dark: `0xFF6EC6FF`, `0xFF6FD8C9`, `0xFF8AA6FF`, `0xFFB39DFF`, `0xFF7FB4CC`, `0xFF9FC0E8`; light: `0xFF2C6FA6`, `0xFF2E8B84`, `0xFF3F52A8`, `0xFF6A4FA8`, `0xFF34738A`, `0xFF4E6E96`), selected by the current color scheme.
- Main window only: `MiniView` passes no arcs (JVM parity). No tap/scrubbing interaction.
- Kotlin lint: `./gradlew ktlintCheck` must pass. Commit messages: English, imperative, change-only; no AI references. Commits succeed unsigned.
- Headless GUI hovering is blocked — the hover behavior is a manual smoke test; report exactly what was and wasn't verified.
- **Before Task 1:** create the working branch: `git checkout -b claude/macos-native-busy-arcs`.

## File map

- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` — `BusyArcSnapshot`, `busyArcs` field + mapping, `busyArcHoverLabel`, `busyArcIndexAt`, `use24Hour` param.
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` — `use24Hour` constructor value, threaded to both `toTodaySnapshot` call sites.
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt` — system clock-format probe.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` — 4 new tests.
- Test (create): `core/src/commonTest/kotlin/fr/dayview/app/BusyArcIndexTest.kt` — hit-helper tests.
- Create: `macos/DayView/BusyPalette.swift` — the cribbed colors.
- Modify: `macos/DayView/DayRingCanvas.swift` — busy lane + shared geometry constants.
- Modify: `macos/DayView/RingView.swift` — pass arcs, hover + floating label.

---

## Task 1: `:core` — arc snapshot, hover label, hit helper, 12/24-h plumbing (TDD)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`
- Test (create): `core/src/commonTest/kotlin/fr/dayview/app/BusyArcIndexTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.busyBlockArcsState` (existing), `BusyBlockArc` (angles/colorIndex/titles/calendarName/start/end), `formatWallClock(hour, minute, use24Hour)` (existing `:core`).
- Produces (Task 2 relies on these): `BusyArcSnapshot(startAngleDegrees: Double, sweepDegrees: Double, colorIndex: Long, hoverLabel: String)`; snapshot field `busyArcs: List<BusyArcSnapshot>`; top-level `busyArcIndexAt(arcs: List<BusyArcSnapshot>, angleDegrees: Double): Int` (Swift: `TodaySnapshotKt.busyArcIndexAt(arcs:angleDegrees:)`, returns `Int32`); `DayViewSession(controller, scope, calendarSource, use24Hour)`.

- [ ] **Step 1: Write the failing tests**

1. Create `core/src/commonTest/kotlin/fr/dayview/app/BusyArcIndexTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class BusyArcIndexTest {
    private fun arc(start: Double, sweep: Double) =
        BusyArcSnapshot(startAngleDegrees = start, sweepDegrees = sweep, colorIndex = 0L, hoverLabel = "x")

    @Test
    fun findsTheContainingArc() {
        assertEquals(0, busyArcIndexAt(listOf(arc(-90.0, 30.0)), -75.0))
    }

    @Test
    fun missesTheGaps() {
        assertEquals(-1, busyArcIndexAt(listOf(arc(-90.0, 30.0)), -20.0))
    }

    @Test
    fun boundariesAreInclusive() {
        val arcs = listOf(arc(-90.0, 30.0))
        assertEquals(0, busyArcIndexAt(arcs, -90.0)) // start edge
        assertEquals(0, busyArcIndexAt(arcs, -60.0)) // end edge
    }

    @Test
    fun wrapsAcrossTheAnchor() {
        // 240° + 40° sweep crosses 270° (= the -90° anchor) and reaches -80°.
        val arcs = listOf(arc(240.0, 40.0))
        assertEquals(0, busyArcIndexAt(arcs, -85.0)) // == 275°, inside the wrapped tail
        assertEquals(-1, busyArcIndexAt(arcs, -60.0)) // == 300°, past the tail
    }

    @Test
    fun emptyListReturnsMinusOne() {
        assertEquals(-1, busyArcIndexAt(emptyList(), 0.0))
    }

    @Test
    fun firstOfOverlappingArcsWins() {
        assertEquals(0, busyArcIndexAt(listOf(arc(0.0, 60.0), arc(30.0, 60.0)), 45.0))
    }
}
```

2. Append inside `DayViewSessionTest` in `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`. Add these imports (the rest exist): `import kotlinx.datetime.TimeZone` and `import kotlinx.datetime.toLocalDateTime`.

```kotlin
    @Test
    fun busyArcsCarryAnglesColorAndHoverLabel() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L) // midday UTC fixture
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = listOf("Standup"), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        val arc = seen.last().busyArcs.single()
        assertEquals(0L, arc.colorIndex)
        assertTrue(arc.sweepDegrees > 0.0)
        assertTrue(arc.startAngleDegrees >= -90.0 && arc.startAngleDegrees < 270.0)
        // Expected label built from the fixture's own local conversion — host-TZ-portable,
        // pins the joining and format wiring (formatWallClock has its own tests).
        val zone = TimeZone.currentSystemDefault()
        val s = now.toLocalDateTime(zone)
        val e = (now + 1.hours).toLocalDateTime(zone)
        assertEquals(
            "Standup · ${formatWallClock(s.hour, s.minute, true)}–${formatWallClock(e.hour, e.minute, true)}",
            arc.hoverLabel,
        )

        sub.cancel()
    }

    @Test
    fun untitledBusyArcFallsBackToTheCalendarName() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = emptyList(), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertTrue(seen.last().busyArcs.single().hoverLabel.startsWith("Work · "))

        sub.cancel()
    }

    @Test
    fun twelveHourSessionFormatsHoverTimesInTwelveHourClock() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = listOf("Standup"), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                netTimeSettings = NetTimeSettings(enabled = true),
            ),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source, use24Hour = false)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        val zone = TimeZone.currentSystemDefault()
        val s = now.toLocalDateTime(zone)
        val e = (now + 1.hours).toLocalDateTime(zone)
        assertEquals(
            "Standup · ${formatWallClock(s.hour, s.minute, false)}–${formatWallClock(e.hour, e.minute, false)}",
            seen.last().busyArcs.single().hoverLabel,
        )

        sub.cancel()
    }

    @Test
    fun busyArcsEmptyWhenNetTimeDisabled() = runTest {
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val source = FakeCalendarSource(
            calendars = listOf(CalendarInfo("c1", "Work")),
            busy = listOf(BusyInterval(now, now + 1.hours, titles = listOf("Standup"), calendarId = "c1")),
        )
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = now,
        )
        val session = DayViewSession(controller, backgroundScope, source)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertTrue(seen.last().busyArcs.isEmpty())

        sub.cancel()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.BusyArcIndexTest' --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: FAIL to compile — `unresolved reference: BusyArcSnapshot` / `busyArcIndexAt` / `busyArcs` / `use24Hour`.

- [ ] **Step 3: Add the snapshot type, field, label, and hit helper**

In `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`:

1. Add these imports at the top (the file currently has none):

```kotlin
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
```

2. Add after the `CalendarChoice` data class (top level):

```kotlin
/** One calendar-busy block projected on the ring, ready for native drawing and hover. */
data class BusyArcSnapshot(
    val startAngleDegrees: Double, // -90° anchor (12 o'clock), clockwise
    val sweepDegrees: Double,
    val colorIndex: Long, // stable per calendar; the UI maps % palette size
    val hoverLabel: String, // "<name> · <start>–<end>", see busyArcHoverLabel
)

/**
 * Index of the arc containing [angleDegrees] (same -90°-anchored clockwise convention as
 * the arcs), or -1. Wrap-aware: an arc whose tail crosses the anchor still matches.
 */
fun busyArcIndexAt(arcs: List<BusyArcSnapshot>, angleDegrees: Double): Int {
    val probe = normalizeRingDegrees(angleDegrees)
    arcs.forEachIndexed { index, arc ->
        val offset = normalizeRingDegrees(probe - normalizeRingDegrees(arc.startAngleDegrees))
        if (offset <= arc.sweepDegrees) return index
    }
    return -1
}

private fun normalizeRingDegrees(degrees: Double): Double {
    val mod = degrees % 360.0
    return if (mod < 0) mod + 360.0 else mod
}

/**
 * Hover text for a busy block: non-blank titles joined, falling back to the calendar
 * name, then "Busy"; times from the EXACT stored instants (an angle round-trip shaves
 * minutes — see BusyBlockArc).
 */
internal fun busyArcHoverLabel(arc: BusyBlockArc, use24Hour: Boolean): String {
    val name = arc.titles.filter { it.isNotBlank() }.joinToString(", ")
        .ifBlank { arc.calendarName }
        .ifBlank { "Busy" }
    val zone = TimeZone.currentSystemDefault()
    val start = arc.start.toLocalDateTime(zone)
    val end = arc.end.toLocalDateTime(zone)
    val startLabel = formatWallClock(start.hour, start.minute, use24Hour)
    val endLabel = formatWallClock(end.hour, end.minute, use24Hour)
    return "$name · $startLabel–$endLabel"
}
```

3. Change the mapping function signature from `internal fun DayViewUiState.toTodaySnapshot(): TodaySnapshot {` to:

```kotlin
internal fun DayViewUiState.toTodaySnapshot(use24Hour: Boolean = true): TodaySnapshot {
```

4. Add to the END of the `TodaySnapshot` constructor parameter list (after `calendars`):

```kotlin
    val busyArcs: List<BusyArcSnapshot>,
```

5. Add to the END of the `TodaySnapshot(...)` construction inside `toTodaySnapshot` (after `calendars = ...,`):

```kotlin
        busyArcs = busyBlockArcsState.map { arc ->
            BusyArcSnapshot(
                startAngleDegrees = arc.startAngleDegrees.toDouble(),
                sweepDegrees = arc.sweepDegrees.toDouble(),
                colorIndex = arc.colorIndex.toLong(),
                hoverLabel = busyArcHoverLabel(arc, use24Hour),
            )
        },
```

- [ ] **Step 4: Thread `use24Hour` through the session**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`:

1. Add a fourth constructor parameter after `calendarSource`:

```kotlin
    private val use24Hour: Boolean = true,
```

2. Update both `toTodaySnapshot()` call sites:
   - `fun currentSnapshot(): TodaySnapshot = controller.stateFlow.value.toTodaySnapshot(use24Hour)`
   - in `subscribe`: `controller.stateFlow.collect { onEach(it.toTodaySnapshot(use24Hour)) }`

- [ ] **Step 5: Detect the system clock format natively**

In `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`:

1. Add imports: `import platform.Foundation.NSDateFormatter`, `import platform.Foundation.NSLocale`, `import platform.Foundation.currentLocale`.

2. Add a private top-level function:

```kotlin
// The canonical macOS probe: format a "j" skeleton for the current locale/settings —
// an "a" (AM/PM) in the result means the system uses the 12-hour clock.
private fun systemUses24HourClock(): Boolean {
    val format = NSDateFormatter.dateFormatFromTemplate("j", options = 0u, locale = NSLocale.currentLocale)
    return format?.contains("a") != true
}
```

3. In `create()`, pass it: `val session = DayViewSession(controller, scope, source, use24Hour = systemUses24HourClock())`.

(Binding-name adaptation, bounded as in Phase 7a: `dateFormatFromTemplate` is an ObjC class method — it may need `NSDateFormatter.Companion.` or an options type cast (`0uL`); adapt names/types only until `:core:compileKotlinMacosArm64` is green; the "j"-template semantics are fixed.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.BusyArcIndexTest' --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: PASS (6 hit-helper tests + all session tests incl. the 4 new ones).

- [ ] **Step 7: Native compile + lint**

Run: `./gradlew :core:compileKotlinMacosArm64 ktlintCheck`
Expected: BUILD SUCCESSFUL (adapt Step 5 binding names if needed; `ktlintFormat` if flagged).

- [ ] **Step 8: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt core/src/commonTest/kotlin/fr/dayview/app/BusyArcIndexTest.kt
git commit -m "feat(core): busy-arc snapshot with hover labels and a wrap-aware hit helper"
```

---

## Task 2: Swift — busy lane on the ring + hover label

**Files:**
- Create: `macos/DayView/BusyPalette.swift`
- Modify: `macos/DayView/DayRingCanvas.swift`
- Modify: `macos/DayView/RingView.swift`

**Interfaces:**
- Consumes: `snapshot.busyArcs` (bridged `[BusyArcSnapshot]` with `startAngleDegrees`/`sweepDegrees`/`colorIndex`/`hoverLabel`); the Kotlin hit helper as `TodaySnapshotKt.busyArcIndexAt(arcs:angleDegrees:)` (returns `Int32`; if the generated facade class is named differently, check `DayViewKit`'s generated header and adapt the class name only).
- Produces: `DayRingCanvas(momentAngleDegrees:lineWidth:inset:busyArcs:)` with static geometry constants shared by the hover math.

- [ ] **Step 1: The palette**

Create `macos/DayView/BusyPalette.swift`:

```swift
import SwiftUI

/// Per-calendar busy colors, cribbed verbatim from the JVM DayViewTheme (colors.busy).
/// Temporary home: the visual-identity pass will fold these into the full native theme.
enum BusyPalette {
    private static let dark: [Color] = [
        rgb(0x6EC6FF), // sky
        rgb(0x6FD8C9), // teal
        rgb(0x8AA6FF), // periwinkle
        rgb(0xB39DFF), // violet
        rgb(0x7FB4CC), // slate cyan
        rgb(0x9FC0E8), // steel
    ]
    private static let light: [Color] = [
        rgb(0x2C6FA6), // sky
        rgb(0x2E8B84), // teal
        rgb(0x3F52A8), // periwinkle
        rgb(0x6A4FA8), // violet
        rgb(0x34738A), // slate cyan
        rgb(0x4E6E96), // steel
    ]

    static func color(for index: Int, scheme: ColorScheme) -> Color {
        let palette = scheme == .dark ? dark : light
        let safe = ((index % palette.count) + palette.count) % palette.count
        return palette[safe]
    }

    private static func rgb(_ hex: UInt32) -> Color {
        Color(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}
```

- [ ] **Step 2: The busy lane in `DayRingCanvas`**

Replace the entire contents of `macos/DayView/DayRingCanvas.swift` with:

```swift
import SwiftUI
import DayViewKit

/// The countdown ring shared by the main window and the mini window: a gray full-circle
/// track, an optional calendar-busy lane (one thin colored arc per merged busy block,
/// concentric inside the main lane — JVM geometry), and the accent remaining-time sweep
/// anchored at 12 o'clock. Drawing only — text, hover, and layout stay with the callers.
struct DayRingCanvas: View {
    let momentAngleDegrees: Double
    var lineWidth: CGFloat = 18
    var inset: CGFloat = 40
    var busyArcs: [BusyArcSnapshot] = []

    @Environment(\.colorScheme) private var colorScheme

    // Busy-lane geometry, shared with RingView's hover hit-testing so the hit band can
    // never drift from the drawn lane (mirrors DayViewTodayScreen.kt:1356).
    static let busyRadiusFactor: CGFloat = 0.95 // lane radius = main radius - lineWidth * this
    static let busyWidthFactor: CGFloat = 0.7 // lane stroke = lineWidth * this

    var body: some View {
        Canvas { context, size in
            let side = min(size.width, size.height) - inset * 2
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = max(side / 2, 1)
            var track = Path()
            track.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
            context.stroke(track, with: .color(.gray.opacity(0.2)), lineWidth: lineWidth)
            // Busy lane: after the track, before the remaining sweep (JVM draw order).
            let busyRadius = radius - lineWidth * Self.busyRadiusFactor
            let busyWidth = lineWidth * Self.busyWidthFactor
            for arc in busyArcs {
                var lane = Path()
                lane.addArc(
                    center: center,
                    radius: busyRadius,
                    startAngle: .degrees(arc.startAngleDegrees),
                    endAngle: .degrees(arc.startAngleDegrees + arc.sweepDegrees),
                    clockwise: false
                )
                context.stroke(
                    lane,
                    with: .color(BusyPalette.color(for: Int(arc.colorIndex), scheme: colorScheme)),
                    style: StrokeStyle(lineWidth: busyWidth, lineCap: .round)
                )
            }
            var sweep = Path()
            sweep.addArc(center: center, radius: radius, startAngle: .degrees(-90), endAngle: .degrees(momentAngleDegrees), clockwise: false)
            context.stroke(sweep, with: .color(.accentColor), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
        }
    }
}
```

(`MiniView` keeps calling `DayRingCanvas(momentAngleDegrees:lineWidth:inset:)` — the `busyArcs` default keeps it arc-free, per JVM parity.)

- [ ] **Step 3: Hover in `RingView`**

In `macos/DayView/RingView.swift`:

1. Add two members after the existing `@State` properties:

```swift
    private struct HoveredBusyArc {
        let label: String
        let position: CGPoint
    }

    @State private var hoveredBusy: HoveredBusyArc?
```

(Note: a `private struct` nested in the `View` struct plus an `@State` — keep the struct declaration ABOVE the `@State` line.)

2. Replace the `ringSection` computed property with:

```swift
    private var ringSection: some View {
        VStack(spacing: 8) {
            GeometryReader { proxy in
                DayRingCanvas(
                    momentAngleDegrees: model.snapshot.momentAngleDegrees,
                    busyArcs: model.snapshot.busyArcs
                )
                .onContinuousHover(coordinateSpace: .local) { phase in
                    switch phase {
                    case .active(let point):
                        hoveredBusy = busyArcHit(at: point, in: proxy.size)
                    case .ended:
                        hoveredBusy = nil
                    }
                }
                .overlay(alignment: .topLeading) {
                    if let hover = hoveredBusy {
                        Text(hover.label)
                            .font(.caption)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                            .offset(x: hover.position.x + 12, y: hover.position.y - 28)
                            .allowsHitTesting(false)
                    }
                }
            }
            .frame(height: 260)
            Text(model.snapshot.dayStatus)
                .font(.system(size: 40, weight: .semibold, design: .rounded))
                .monospacedDigit()
            if !model.snapshot.secondsLabel.isEmpty {
                Text(model.snapshot.secondsLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            if !model.snapshot.netTimeLabel.isEmpty {
                Text(model.snapshot.netTimeLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
    }

    // Geometry half of the hit test (the angle-containment half is Kotlin's
    // busyArcIndexAt): pointer -> polar, radius must sit on the busy lane band.
    // Constants come from DayRingCanvas so drawing and hit-testing cannot drift.
    private func busyArcHit(at point: CGPoint, in size: CGSize) -> HoveredBusyArc? {
        let arcs = model.snapshot.busyArcs
        guard !arcs.isEmpty else { return nil }
        let inset: CGFloat = 40
        let lineWidth: CGFloat = 18
        let side = min(size.width, size.height) - inset * 2
        let radius = max(side / 2, 1)
        let busyRadius = radius - lineWidth * DayRingCanvas.busyRadiusFactor
        let busyWidth = lineWidth * DayRingCanvas.busyWidthFactor
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let dx = point.x - center.x
        let dy = point.y - center.y
        let distance = (dx * dx + dy * dy).squareRoot()
        guard abs(distance - busyRadius) <= busyWidth / 2 + 6 else { return nil }
        // y-down atan2 yields the same clockwise-from-3-o'clock convention the arcs use.
        let angle = Double(atan2(dy, dx)) * 180.0 / .pi
        let index = Int(TodaySnapshotKt.busyArcIndexAt(arcs: arcs, angleDegrees: angle))
        guard index >= 0, index < arcs.count else { return nil }
        return HoveredBusyArc(label: arcs[index].hoverLabel, position: point)
    }
```

(If the compiler reports no `TodaySnapshotKt`, look in the generated `DayViewKit` Swift interface for the facade class that hosts the top-level `busyArcIndexAt` and adapt the class name only. If `Foundation`'s `atan2` is not in scope, `import Foundation` at the top of the file.)

- [ ] **Step 4: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches; without busy events (or net time off) the ring renders exactly as before. Confirm process alive (`pgrep -f 'Debug/DayView.app'`).

- [ ] **Step 5: Verify what the environment allows, report the rest as manual**

The **manual smoke test** (GUI hover cannot be driven in-sandbox) — report as not-verified:

1. With net time on and busy events today: thin colored arcs appear on the inner lane of the main ring at the events' positions; two calendars → two stable colors.
2. Hovering an arc shows "title · start–end" near the cursor; leaving hides it; the times match the events exactly (no ±1-minute drift) and follow the system 12/24-hour setting.
3. Untitled events show the calendar name.
4. The mini window's ring stays arc-free.
5. Ring, seconds line, and net line render as before when net time is off.

Close afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 6: Commit**

```bash
git add macos/DayView/BusyPalette.swift macos/DayView/DayRingCanvas.swift macos/DayView/RingView.swift
git commit -m "feat(macos): calendar busy lane on the main ring with hover labels"
```

---

## Self-Review Notes

- **Spec coverage:** `BusyArcSnapshot` + `busyArcs` mapping from `busyBlockArcsState` → Task 1 Step 3; Kotlin `hoverLabel` from exact Instants with title→calendar→"Busy" fallback → `busyArcHoverLabel` (pinned by three label tests incl. the TZ-portable exact-string assertion and the 12-hour variant); `use24Hour` plumbing + native "j"-probe → Task 1 Steps 4–5; wrap-aware `busyArcIndexAt` under `jvmTest` → `BusyArcIndexTest` (6 tests incl. anchor wrap and overlap order); JVM lane geometry + palette + draw order → Task 2 Step 2 with shared static factors; hover (radius band Swift-side, angle Kotlin-side, floating caption) → Task 2 Step 3; main-window-only (mini default keeps it arc-free) → Step 2 note; disabled ⇒ empty (controller gating, no new logic) → pinned by `busyArcsEmptyWhenNetTimeDisabled`.
- **Type consistency:** `BusyArcSnapshot(startAngleDegrees: Double, sweepDegrees: Double, colorIndex: Long, hoverLabel: String)` matches the Swift usage (`Int(arc.colorIndex)`, `arc.hoverLabel`); `busyArcIndexAt(arcs, angleDegrees)` ↔ `TodaySnapshotKt.busyArcIndexAt(arcs:angleDegrees:)` returning `Int32` cast to `Int`; the session's 4th param name `use24Hour` matches the test call.
- **No placeholders:** all steps carry complete code; the two bounded adaptation notes (K/N `NSDateFormatter` binding, the Swift facade class name) name the exact fallback procedure.
- **YAGNI:** no tap interaction, no mini arcs, no detour/focus lanes, no full theming (only `BusyPalette` constants).
- **Known nuance:** the hover geometry duplicates `inset`/`lineWidth` literals (40/18) from the call site — acceptable because `RingView` calls `DayRingCanvas` with defaults; the shared `busyRadiusFactor`/`busyWidthFactor` statics carry the lane-specific math.
