# macOS Native Detour Ring Bodies + Hover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw the detour episodes as colored bodies on an outer lane of the native main ring, with hover showing the motif and times — the outer-lane analogue of the busy arcs (7b).

**Architecture:** `TodaySnapshot` gains `detourBodies: List<DetourBodySnapshot>` mapped from the controller's `detourBodiesState`, plus a shared `angularArcIndexAt` helper (extracted from `busyArcIndexAt`) that `detourBodyIndexAt` reuses at 6° (Task 1). `DayRingCanvas` draws the outer glow+core lane and `RingView`'s hover extends to a second (outer) band (Task 2). Main window only.

**Tech Stack:** Kotlin Multiplatform (`:core`), SwiftUI (macOS 15+).

## Global Constraints

- NO controller change — `detourBodiesState` consumed as-is (already floored to a visible minimum, midpoint-outside episodes dropped, colored from `detourSources`; day-gated ⇒ empty when none).
- Hover label = `"<category> · <start> – <end>"`; category is always non-blank (`addDetour`/`updateDetour` reject blank); times from the body's exact `start`/`end` Instants via `formatWallClock(hour, minute, use24Hour)` — never from angles.
- Angle convention: −90° anchor, clockwise, wrap-aware — identical for busy arcs, detour bodies, the Canvas, and `atan2` in y-down space.
- Detour lane geometry (mirror of the inner busy lane, JVM `DayViewTodayScreen.kt:1384`): outer radius = main radius **+** `lineWidth × 0.95`, glow stroke `lineWidth × 0.7` @ 0.16, core stroke `lineWidth × 0.42` @ 0.92, round caps, drawn after the busy lane. Color = `palette.detourColor(colorIndex)`.
- Shared hit helper: `angularArcIndexAt(starts, sweeps, angleDegrees, toleranceDegrees)` carries the wrap-aware nearest-arc logic; `busyArcIndexAt` delegates at 5.0, `detourBodyIndexAt` at 6.0. `busyArcIndexAt`'s existing `BusyArcIndexTest` MUST stay unmodified (the regression guard).
- Drawing and hit-testing share the `DayRingCanvas` geometry statics so the hit band can't drift; the two hover bands (outer=detour, inner=busy) are radius-exclusive, detour checked first.
- Main window only: `MiniView` passes no detour bodies. No tap/scrubbing.
- Kotlin lint: `./gradlew ktlintCheck`. Commit messages English/imperative/change-only, no AI references; commits succeed unsigned.
- Headless GUI blocked — hover is a manual smoke test; report what was and wasn't verified.
- **Before Task 1:** `git checkout -b claude/macos-native-detour-bodies`.

## File map

- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` — `DetourBodySnapshot`, `detourBodies` field + mapping, `angularArcIndexAt`, `busyArcIndexAt` delegate, `detourBodyIndexAt`.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/BusyArcIndexTest.kt` — unchanged (regression guard).
- Test (create): `core/src/commonTest/kotlin/fr/dayview/app/AngularArcIndexTest.kt` — generic + detour helper.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` — detour-body mapping.
- Modify: `macos/DayView/DayRingCanvas.swift` — outer detour lane + `detourRadiusFactor` static.
- Modify: `macos/DayView/RingView.swift` — pass `detourBodies`; two-band hover.

---

## Task 1: `:core` — detour-body snapshot + shared hit helper (TDD)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Test (create): `core/src/commonTest/kotlin/fr/dayview/app/AngularArcIndexTest.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.detourBodiesState` (`List<DetourBody>`: startAngleDegrees/sweepDegrees Float, colorIndex Int, category, description, start/end Instant); `formatWallClock`.
- Produces (Task 2): `DetourBodySnapshot(startAngleDegrees: Double, sweepDegrees: Double, colorIndex: Long, hoverLabel: String)`; snapshot field `detourBodies`; `angularArcIndexAt(starts: List<Double>, sweeps: List<Double>, angleDegrees: Double, toleranceDegrees: Double): Int`; `detourBodyIndexAt(bodies: List<DetourBodySnapshot>, angleDegrees: Double): Int` (Swift: `TodaySnapshotKt.detourBodyIndexAt(bodies:angleDegrees:)` → `Int32`); `busyArcIndexAt` unchanged signature.

- [ ] **Step 1: Write the failing tests**

1. Create `core/src/commonTest/kotlin/fr/dayview/app/AngularArcIndexTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AngularArcIndexTest {
    @Test
    fun containmentAndGap() {
        assertEquals(0, angularArcIndexAt(listOf(-90.0), listOf(30.0), -75.0, 5.0))
        assertEquals(-1, angularArcIndexAt(listOf(-90.0), listOf(30.0), -20.0, 5.0))
    }

    @Test
    fun wrapsAcrossTheAnchor() {
        assertEquals(0, angularArcIndexAt(listOf(240.0), listOf(40.0), -85.0, 5.0))
    }

    @Test
    fun toleranceBoundaryIsInclusive() {
        // 3° sliver at 0°; probe 6° past the end is within a 6° tolerance, outside a 5° one.
        assertEquals(0, angularArcIndexAt(listOf(0.0), listOf(3.0), 9.0, 6.0))
        assertEquals(-1, angularArcIndexAt(listOf(0.0), listOf(3.0), 9.0, 5.0))
    }

    @Test
    fun emptyIsMinusOne() {
        assertEquals(-1, angularArcIndexAt(emptyList(), emptyList(), 0.0, 6.0))
    }

    @Test
    fun detourBodyHelperUsesSixDegreeTolerance() {
        val bodies = listOf(
            DetourBodySnapshot(startAngleDegrees = 0.0, sweepDegrees = 3.0, colorIndex = 0L, hoverLabel = "x"),
        )
        assertEquals(0, detourBodyIndexAt(bodies, 9.0))  // 6° past the end: within 6°
        assertEquals(-1, detourBodyIndexAt(bodies, 11.0)) // 8° past: outside
    }
}
```

2. Append inside `DayViewSessionTest` in `DayViewSessionTest.kt` (imports present):

```kotlin
    @Test
    fun detourBodiesCarryAnglesColorAndHoverLabel() = runTest {
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
        val body = seen.last().detourBodies.single()
        assertEquals(0L, body.colorIndex)
        assertTrue(body.sweepDegrees > 0.0)
        // Label from the episode's own instants (the entry carries the same span).
        val entry = seen.last().detours.single()
        val zone = TimeZone.currentSystemDefault()
        val start = Instant.fromEpochMilliseconds(entry.startEpochMillis).toLocalDateTime(zone)
        val end = Instant.fromEpochMilliseconds(entry.endEpochMillis).toLocalDateTime(zone)
        assertEquals(
            "Call · ${formatWallClock(start.hour, start.minute, true)} – ${formatWallClock(end.hour, end.minute, true)}",
            body.hoverLabel,
        )

        sub.cancel()
    }

    @Test
    fun detourBodiesEmptyWithNoDetours() = runTest {
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()
        assertTrue(seen.last().detourBodies.isEmpty())
        sub.cancel()
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.AngularArcIndexTest' --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: FAIL to compile — unresolved `DetourBodySnapshot`/`detourBodies`/`angularArcIndexAt`/`detourBodyIndexAt`.

- [ ] **Step 3: Refactor the hit helper + add the detour helper**

In `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`, replace the existing `busyArcIndexAt` function (lines 40–65, from the KDoc through its closing brace) with:

```kotlin
/**
 * Index of the arc (parallel [starts]/[sweeps], same length) containing [angleDegrees], or
 * the nearest whose edge is within [toleranceDegrees] — the JVM hover margin: a short arc is
 * a few-degree sliver, and pure containment would make it a pixel-wide target. Ring
 * convention: -90° anchor, clockwise, wrap-aware. -1 when nothing is within tolerance.
 */
fun angularArcIndexAt(
    starts: List<Double>,
    sweeps: List<Double>,
    angleDegrees: Double,
    toleranceDegrees: Double,
): Int {
    val probe = normalizeRingDegrees(angleDegrees)
    var best = -1
    var bestDistance = Double.MAX_VALUE
    for (index in starts.indices) {
        val offset = normalizeRingDegrees(probe - normalizeRingDegrees(starts[index]))
        val distance = if (offset <= sweeps[index]) {
            0.0
        } else {
            // Angular distance to the nearer edge, wrap-aware: past the end going
            // clockwise, or back around to the start going counter-clockwise.
            minOf(offset - sweeps[index], 360.0 - offset)
        }
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return if (bestDistance <= toleranceDegrees) best else -1
}

/** Busy-arc pick at the 5° hover margin. */
fun busyArcIndexAt(arcs: List<BusyArcSnapshot>, angleDegrees: Double): Int =
    angularArcIndexAt(arcs.map { it.startAngleDegrees }, arcs.map { it.sweepDegrees }, angleDegrees, 5.0)

/** Detour-body pick at the 6° hover margin (the JVM detour tolerance). */
fun detourBodyIndexAt(bodies: List<DetourBodySnapshot>, angleDegrees: Double): Int =
    angularArcIndexAt(bodies.map { it.startAngleDegrees }, bodies.map { it.sweepDegrees }, angleDegrees, 6.0)
```

(Delete the now-unused `private const val BUSY_HOVER_TOLERANCE_DEGREES`. Keep `normalizeRingDegrees` — it's now used by `angularArcIndexAt`.)

- [ ] **Step 4: Add `DetourBodySnapshot` and the snapshot field**

In the same file:

1. Add after the `DetourEntry` data class (top level):

```kotlin
/** One detour episode projected on the ring's outer lane, ready for drawing and hover. */
data class DetourBodySnapshot(
    val startAngleDegrees: Double, // -90° anchor, clockwise
    val sweepDegrees: Double, // already floored to a visible minimum by :core
    val colorIndex: Long, // matches the tally source color
    val hoverLabel: String, // "<motif> · <start> – <end>"
)
```

2. Add to the END of the `TodaySnapshot` constructor parameter list (after `detours`):

```kotlin
    val detourBodies: List<DetourBodySnapshot>,
```

3. Add to the END of the `toTodaySnapshot` construction (after `detours = ...,`):

```kotlin
        detourBodies = detourBodiesState.map { body ->
            val zone = TimeZone.currentSystemDefault()
            val start = body.start.toLocalDateTime(zone)
            val end = body.end.toLocalDateTime(zone)
            DetourBodySnapshot(
                startAngleDegrees = body.startAngleDegrees.toDouble(),
                sweepDegrees = body.sweepDegrees.toDouble(),
                colorIndex = body.colorIndex.toLong(),
                hoverLabel = "${body.category} · ${formatWallClock(start.hour, start.minute, use24Hour)} – " +
                    formatWallClock(end.hour, end.minute, use24Hour),
            )
        },
```

(`detourBodiesState` is a `DayViewUiState` member; `TimeZone`/`toLocalDateTime` already imported.)

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.AngularArcIndexTest' --tests 'fr.dayview.app.DayViewSessionTest' --tests 'fr.dayview.app.BusyArcIndexTest'`
Expected: PASS — the new tests, the detour session tests, AND `BusyArcIndexTest` unchanged (the delegate preserves the 5° behavior).

- [ ] **Step 6: Lint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (`ktlintFormat` if flagged).

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonTest/kotlin/fr/dayview/app/AngularArcIndexTest.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): detour-body snapshot and a shared angular hit helper"
```

---

## Task 2: Swift — outer detour lane + two-band hover

**Files:**
- Modify: `macos/DayView/DayRingCanvas.swift`
- Modify: `macos/DayView/RingView.swift`

**Interfaces:**
- Consumes: `snapshot.detourBodies` (`[DetourBodySnapshot]`); `TodaySnapshotKt.detourBodyIndexAt(bodies:angleDegrees:)` (→ `Int32`); `DayViewPalette.detourColor(_:)` (from 9a).
- Produces: `DayRingCanvas(..., detourBodies:)` + `detourRadiusFactor` static; a two-band `RingView` hover.

- [ ] **Step 1: `DayRingCanvas` — the outer detour lane**

In `macos/DayView/DayRingCanvas.swift`:

1. Add the parameter after `busyArcs`:

```swift
    var detourBodies: [DetourBodySnapshot] = []
```

2. Add a static next to `busyRadiusFactor`/`busyWidthFactor`:

```swift
    static let detourRadiusFactor: CGFloat = 0.95
```

3. In `body`, immediately after the busy-lane `for arc in busyArcs { ... }` loop, add the outer detour lane:

```swift
            let detourRadius = radius + lineWidth * Self.detourRadiusFactor
            for body in detourBodies {
                let color = palette.detourColor(Int(body.colorIndex))
                var lane = Path()
                lane.addArc(center: center, radius: detourRadius, startAngle: .degrees(body.startAngleDegrees), endAngle: .degrees(body.startAngleDegrees + body.sweepDegrees), clockwise: false)
                context.stroke(lane, with: .color(color.opacity(0.16)), style: StrokeStyle(lineWidth: lineWidth * 0.7, lineCap: .round))
                context.stroke(lane, with: .color(color.opacity(0.92)), style: StrokeStyle(lineWidth: lineWidth * 0.42, lineCap: .round))
            }
```

(`palette` is the local from the busy-lane block; if it is scoped there, reuse the same `DayViewPalette.current(for: colorScheme)` binding at the top of `body`. Confirm the `palette` local is visible; if not, add `let palette = DayViewPalette.current(for: colorScheme)` once near the top of the `Canvas` closure and use it for both lanes.)

- [ ] **Step 2: `RingView` — pass the bodies + generalize the hover to two bands**

In `macos/DayView/RingView.swift`:

1. In `ringSection`, add the `detourBodies` argument to the `DayRingCanvas(...)` call:

```swift
                    busyArcs: model.snapshot.busyArcs,
                    detourBodies: model.snapshot.detourBodies
```

2. Rename the hover struct to be lane-agnostic and keep the state name working — change:

```swift
    private struct HoveredBusyArc {
        let label: String
        let position: CGPoint
    }

    @State private var hoveredBusy: HoveredBusyArc?
```

to:

```swift
    private struct HoveredArc {
        let label: String
        let position: CGPoint
    }

    @State private var hoveredArc: HoveredArc?
```

and update the two `.onContinuousHover` assignments (`hoveredBusy = busyArcHit(...)` → `hoveredArc = arcHit(...)`, `hoveredBusy = nil` → `hoveredArc = nil`) and the overlay `if let hover = hoveredBusy` → `if let hover = hoveredArc`.

3. Replace the `busyArcHit(at:in:)` function with a two-band `arcHit` (detour outer band first, then busy inner band):

```swift
    // Geometry half of the hit test (angular containment is Kotlin's angularArcIndexAt):
    // pointer -> polar, then whichever lane band the radius sits on. Detour rides an outer
    // lane, busy an inner one; constants come from DayRingCanvas so drawing and hit-testing
    // cannot drift.
    private func arcHit(at point: CGPoint, in size: CGSize) -> HoveredArc? {
        let inset = DayRingCanvas.defaultInset
        let lineWidth = DayRingCanvas.defaultLineWidth
        let side = min(size.width, size.height) - inset * 2
        let radius = max(side / 2, 1)
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let dx = point.x - center.x
        let dy = point.y - center.y
        let distance = (dx * dx + dy * dy).squareRoot()
        // y-down atan2 yields the same clockwise-from-3-o'clock convention the arcs use.
        let angle = Double(atan2(dy, dx)) * 180.0 / .pi
        let bandHalf = lineWidth * DayRingCanvas.busyWidthFactor / 2 + 6

        // Detour lane (outer) first.
        let detours = model.snapshot.detourBodies
        let detourRadius = radius + lineWidth * DayRingCanvas.detourRadiusFactor
        if !detours.isEmpty, abs(distance - detourRadius) <= bandHalf {
            let index = Int(TodaySnapshotKt.detourBodyIndexAt(bodies: detours, angleDegrees: angle))
            if index >= 0, index < detours.count {
                return HoveredArc(label: detours[index].hoverLabel, position: point)
            }
        }

        // Busy lane (inner).
        let arcs = model.snapshot.busyArcs
        let busyRadius = radius - lineWidth * DayRingCanvas.busyRadiusFactor
        if !arcs.isEmpty, abs(distance - busyRadius) <= bandHalf {
            let index = Int(TodaySnapshotKt.busyArcIndexAt(arcs: arcs, angleDegrees: angle))
            if index >= 0, index < arcs.count {
                return HoveredArc(label: arcs[index].hoverLabel, position: point)
            }
        }
        return nil
    }
```

- [ ] **Step 3: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches; with no detours the ring is unchanged.

- [ ] **Step 4: Verify what the environment allows, report the rest as manual**

Automatic: build, launch, process alive (`pgrep -f 'Debug/DayView.app'`). The **manual smoke test** (GUI hover blocked in-sandbox) — report as not-verified:

1. Declare a detour → a colored body appears on the **outer** lane at the episode's position, its color matching that source's tally chip.
2. A second source → a second stable color.
3. Hovering a body shows "motif · start – end" to the minute, honoring the system 12/24-hour setting; it disappears on leave.
4. Busy-arc hover still works on the **inner** lane (net time on, a busy event today).
5. The mini window's ring has no detour bodies.

Close afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 5: Commit**

```bash
git add macos/DayView/DayRingCanvas.swift macos/DayView/RingView.swift
git commit -m "feat(macos): detour bodies on the ring's outer lane with hover"
```

---

## Self-Review Notes

- **Spec coverage:** `DetourBodySnapshot` + `detourBodies` mapping from `detourBodiesState` → Task 1 Step 4; Kotlin `hoverLabel` from exact Instants → same, pinned by `detourBodiesCarryAnglesColorAndHoverLabel` (TZ-portable exact string) + `detourBodiesEmptyWithNoDetours`; shared `angularArcIndexAt` with busy(5°)/detour(6°) delegates → Task 1 Step 3, pinned by `AngularArcIndexTest` incl. the 5-vs-6 tolerance boundary, and `BusyArcIndexTest` unchanged as the regression guard; outer glow+core lane at `radius + lineWidth×0.95` → Task 2 Step 1; two-band hover (detour outer, busy inner, radius-exclusive) → Task 2 Step 2; main-window-only (MiniView passes nothing) → Task 2 Step 2 note; disabled/none ⇒ empty (controller day-gating) → `detourBodiesEmptyWithNoDetours`.
- **Type consistency:** `DetourBodySnapshot(startAngleDegrees: Double, sweepDegrees: Double, colorIndex: Long, hoverLabel: String)` matches Swift usage (`Int(body.colorIndex)`, `body.hoverLabel`); `detourBodyIndexAt(bodies, angle)` ↔ `TodaySnapshotKt.detourBodyIndexAt(bodies:angleDegrees:)` returning `Int32`; `busyArcIndexAt` signature unchanged (Swift call site untouched); `detourRadiusFactor` static mirrors `busyRadiusFactor`.
- **No placeholders:** every step carries complete code; Task 2 Step 1 names the `palette`-scope check explicitly.
- **YAGNI:** no off-window tag, no tap, no mini bodies, no focus/engaged arcs.
- **Known nuance:** both hover bands use the busy `busyWidthFactor`-derived `bandHalf`; the detour glow stroke (0.7×lineWidth) matches the busy stroke, so one band tolerance fits both lanes. The bands are at different radii (inner `radius − …`, outer `radius + …`) so they never overlap for a given pointer.
