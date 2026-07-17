# macOS Native Visual Identity Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the DayView visual identity to the native product windows (main + mini): the ink/glow palette, the fully-layered dial with the countdown inside it, and panel-style cards — Settings and the menu dropdown stay system-styled.

**Architecture:** One tiny `:core` addition (`TodaySnapshot.hasStarted`) feeds the dial (Task 1). A `DayViewPalette.swift` (replacing `BusyPalette.swift`) plus `DayRingCanvas` v2 render the full JVM dial (Task 2). `RingView` and `MiniView` move the countdown inside the dial and adopt the glow background + panel cards (Task 3).

**Tech Stack:** Kotlin Multiplatform (`:core`), SwiftUI (macOS 15+).

## Global Constraints

- Presentation-only over existing data; the ONLY `:core` change is adding `hasStarted: Boolean` to `TodaySnapshot` (from `DayProgress.hasStarted`, already `now >= start`). No controller/bridge behavior change.
- Palette values are EXACTLY `DayViewTheme.kt` (see the table in Task 2 Step 1); dark and light variants; selected by the SwiftUI `colorScheme` (already honors `themeMode` via `preferredColorScheme`).
- Dial draw order and constants are verbatim from `DayViewTodayScreen.kt` (per-layer references in Task 2 Step 3). Layers absent by design: focus bands/arcs, detour lane, scrub marker.
- Accent selection: `amber` when `remainingRatio < 0.2`, else `mint`.
- Product windows only: `SettingsView` and `MenuBarContent` are NOT restyled.
- Countdown moves inside the dial; the text below the ring is removed. Hover captions still float over the dial (busy-arc hover from 7b unchanged).
- `BusyPalette.swift` is deleted; its call site (`DayRingCanvas`) uses the new palette's `busy` list.
- Native UI copy hardcoded English. Kotlin lint: `./gradlew ktlintCheck` must pass.
- Commit messages: English, imperative, change-only; no Claude/Anthropic/AI references. Commits succeed unsigned.
- Headless GUI is blocked — the visual result is a manual smoke test (both windows, light + dark, the day-state variations); report exactly what was and wasn't verified.
- **Before Task 1:** create the working branch: `git checkout -b claude/macos-native-visual-identity`.

## File map

- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` — `hasStarted` field.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` — 1 test.
- Create: `macos/DayView/DayViewPalette.swift` — full palette (dark + light).
- Delete: `macos/DayView/BusyPalette.swift`.
- Modify: `macos/DayView/DayRingCanvas.swift` — v2 dial; `defaultInset`/`defaultLineWidth` statics; palette-driven.
- Modify: `macos/DayView/RingView.swift` — dial args, interior countdown, glow bg, panel cards, hit-test uses the statics.
- Modify: `macos/DayView/MiniView.swift` — dial args, interior countdown, glow bg, panel cards.

---

## Task 1: `:core` — `hasStarted` on the snapshot (TDD)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.dayProgress.hasStarted` (existing).
- Produces: `TodaySnapshot.hasStarted: Boolean` (Task 2/3 read it, Swift `hasStarted`).

- [ ] **Step 1: Write the failing test**

Append inside `DayViewSessionTest` in `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`:

```kotlin
    @Test
    fun hasStartedReflectsTheDayWindow() = runTest {
        // Window 00:00-23:59: the fixture instant is mid-day, so the day has started.
        val started = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val startedSession = DayViewSession(started, backgroundScope)
        val startedSeen = mutableListOf<TodaySnapshot>()
        val sub1 = startedSession.subscribe { startedSeen.add(it) }
        runCurrent()
        assertEquals(true, startedSeen.last().hasStarted)
        sub1.cancel()

        // Window 23:00-23:59: at the same mid-day instant the day has NOT started yet.
        val notYet = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 23 * 60, endMinutes = 1439),
            initialNow = Instant.fromEpochMilliseconds(1_699_956_000_000L),
        )
        val notYetSession = DayViewSession(notYet, backgroundScope)
        val notYetSeen = mutableListOf<TodaySnapshot>()
        val sub2 = notYetSession.subscribe { notYetSeen.add(it) }
        runCurrent()
        assertEquals(false, notYetSeen.last().hasStarted)
        sub2.cancel()
    }
```

(The 1_699_956_000_000L instant is the repo's established midday-UTC fixture; a 23:00-start window has not begun at midday in any host zone.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: FAIL to compile — `unresolved reference: hasStarted`.

- [ ] **Step 3: Add the field**

In `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`:

1. Add to the END of the `TodaySnapshot` constructor parameter list (after `busyArcs`):

```kotlin
    val hasStarted: Boolean,
```

2. Add to the END of the `TodaySnapshot(...)` construction inside `toTodaySnapshot` (after `busyArcs = ...,`):

```kotlin
        hasStarted = progress.hasStarted,
```

(`progress` is the existing `dayProgress` local in `toTodaySnapshot`.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: PASS (all tests, incl. the new one).

- [ ] **Step 5: Lint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): expose hasStarted on the today snapshot"
```

---

## Task 2: `DayViewPalette` + the full dial (`DayRingCanvas` v2)

**Files:**
- Create: `macos/DayView/DayViewPalette.swift`
- Delete: `macos/DayView/BusyPalette.swift`
- Modify: `macos/DayView/DayRingCanvas.swift`

**Interfaces:**
- Consumes: `snapshot.hasStarted` (Task 1); existing `momentAngleDegrees`, `remainingRatio`, `isFinished`, `busyArcs`.
- Produces: `DayViewPalette` (static `dark`/`light`, `current(for:)`, fields incl. `busy: [Color]`); `DayRingCanvas(momentAngleDegrees:remainingRatio:isFinished:hasStarted:hasGoal:busyArcs:lineWidth:inset:)` with statics `defaultInset = 40`, `defaultLineWidth = 18`, `busyRadiusFactor = 0.95`, `busyWidthFactor = 0.7`.

- [ ] **Step 1: The palette**

Create `macos/DayView/DayViewPalette.swift`:

```swift
import SwiftUI

/// The DayView palette, mirroring the shared DayViewTheme (dark + light) verbatim.
/// One source of truth for the native product windows; the effective ColorScheme
/// (already honoring the themeMode setting) selects the variant.
struct DayViewPalette {
    let ink: Color
    let panel: Color
    let cloud: Color
    let muted: Color
    let mint: Color
    let amber: Color
    let red: Color
    let glow: Color
    let overlay: Color
    let busy: [Color]

    static func current(for scheme: ColorScheme) -> DayViewPalette {
        scheme == .dark ? dark : light
    }

    static let dark = DayViewPalette(
        ink: hex(0x0B0D12), panel: hex(0x14171E), cloud: hex(0xF3F1EB), muted: hex(0x8B909B),
        mint: hex(0x78E6BD), amber: hex(0xFFB86B), red: hex(0xFF7272), glow: hex(0x171B22),
        overlay: .white,
        busy: [hex(0x6EC6FF), hex(0x6FD8C9), hex(0x8AA6FF), hex(0xB39DFF), hex(0x7FB4CC), hex(0x9FC0E8)]
    )

    static let light = DayViewPalette(
        ink: hex(0xF4F2EC), panel: hex(0xFFFFFF), cloud: hex(0x19201D), muted: hex(0x68716D),
        mint: hex(0x168866), amber: hex(0xB76218), red: hex(0xC74646), glow: hex(0xDCEAE3),
        overlay: hex(0x16211D),
        busy: [hex(0x2C6FA6), hex(0x2E8B84), hex(0x3F52A8), hex(0x6A4FA8), hex(0x34738A), hex(0x4E6E96)]
    )

    func busyColor(_ index: Int) -> Color {
        let safe = ((index % busy.count) + busy.count) % busy.count
        return busy[safe]
    }

    private static func hex(_ value: UInt32) -> Color {
        Color(
            red: Double((value >> 16) & 0xFF) / 255.0,
            green: Double((value >> 8) & 0xFF) / 255.0,
            blue: Double(value & 0xFF) / 255.0
        )
    }
}
```

- [ ] **Step 2: Delete `BusyPalette.swift`**

Run: `git rm macos/DayView/BusyPalette.swift`

- [ ] **Step 3: `DayRingCanvas` v2 — the full dial**

Replace the entire contents of `macos/DayView/DayRingCanvas.swift` with:

```swift
import SwiftUI
import DayViewKit

/// The DayView dial, shared by the main and mini windows. Draws (in order) the track, an
/// optional goal halo, 24 hour ticks, the ratio-accented remaining sweep (a rotated
/// gradient once the day has started, else a uniform ring), the moment marker, the
/// finished rest state, and the calendar-busy lane (glow + core). Constants mirror the
/// JVM CountdownCircle. Drawing only — the interior text and hover live with the callers.
struct DayRingCanvas: View {
    let momentAngleDegrees: Double
    let remainingRatio: Double
    let isFinished: Bool
    let hasStarted: Bool
    var hasGoal: Bool = false
    var busyArcs: [BusyArcSnapshot] = []
    var lineWidth: CGFloat = DayRingCanvas.defaultLineWidth
    var inset: CGFloat = DayRingCanvas.defaultInset

    @Environment(\.colorScheme) private var colorScheme

    // Shared geometry, referenced by RingView's hover hit-testing so the drawn lane and the
    // hit band cannot drift.
    static let defaultInset: CGFloat = 40
    static let defaultLineWidth: CGFloat = 18
    static let busyRadiusFactor: CGFloat = 0.95
    static let busyWidthFactor: CGFloat = 0.7

    var body: some View {
        Canvas { context, size in
            let palette = DayViewPalette.current(for: colorScheme)
            let side = min(size.width, size.height) - inset * 2
            let radius = max(side / 2, 1)
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let accent = remainingRatio < 0.2 ? palette.amber : palette.mint

            // 1. Track.
            stroke(&context, circleAt: center, radius: radius, color: palette.overlay.opacity(0.075))

            // 2. Goal halo.
            if hasGoal {
                let haloRadius = min(size.width, size.height) * 0.30
                var halo = Path()
                halo.addArc(center: center, radius: haloRadius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
                context.fill(
                    halo,
                    with: .radialGradient(
                        Gradient(colors: [palette.amber.opacity(0.10), .clear]),
                        center: center, startRadius: 0, endRadius: haloRadius
                    )
                )
            }

            // 3. Hour ticks (24, majors every 90°).
            let outer = min(size.width, size.height) / 2 - 1
            for i in 0..<24 {
                let major = i % 6 == 0
                let angle = Double(i) * 15.0 - 90.0
                let inner = outer - (major ? 10 : 5)
                let a = angle * .pi / 180.0
                var tick = Path()
                tick.move(to: CGPoint(x: center.x + cos(a) * inner, y: center.y + sin(a) * inner))
                tick.addLine(to: CGPoint(x: center.x + cos(a) * outer, y: center.y + sin(a) * outer))
                context.stroke(tick, with: .color(palette.overlay.opacity(major ? 0.28 : 0.12)), lineWidth: 1)
            }

            if !isFinished && remainingRatio > 0 {
                // 4. Remaining sweep.
                let sweepDegrees = remainingRatio * 360.0
                if hasStarted {
                    // Rotate the drawing so the gradient seam falls at the arc's start (the
                    // gap in the ring) rather than mid-arc.
                    context.drawLayer { layer in
                        layer.translateBy(x: center.x, y: center.y)
                        layer.rotate(by: .degrees(momentAngleDegrees))
                        layer.translateBy(x: -center.x, y: -center.y)
                        var sweep = Path()
                        sweep.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(sweepDegrees), clockwise: false)
                        layer.stroke(
                            sweep,
                            with: .conicGradient(
                                Gradient(colors: [accent, accent.opacity(0.62)]),
                                center: center, angle: .degrees(0)
                            ),
                            style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                        )
                    }
                    // 5. Moment marker.
                    let a = momentAngleDegrees * .pi / 180.0
                    let markerCenter = CGPoint(x: center.x + cos(a) * radius, y: center.y + sin(a) * radius)
                    fillCircle(&context, at: markerCenter, radius: lineWidth * 0.68, color: palette.amber.opacity(0.2))
                    fillCircle(&context, at: markerCenter, radius: lineWidth * 0.4, color: palette.amber)
                    let hi = CGPoint(x: markerCenter.x - lineWidth * 0.1, y: markerCenter.y - lineWidth * 0.1)
                    fillCircle(&context, at: hi, radius: lineWidth * 0.1, color: .white.opacity(0.45))
                } else {
                    // Before the day starts: uniform full ring (no gradient seam).
                    var full = Path()
                    full.addArc(center: center, radius: radius, startAngle: .degrees(-90), endAngle: .degrees(-90 + sweepDegrees), clockwise: false)
                    context.stroke(full, with: .color(accent), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                }
            } else if isFinished {
                // 6. Finished rest state: calm neutral ring + resting marker at 12 o'clock.
                stroke(&context, circleAt: center, radius: radius, color: palette.overlay.opacity(0.16))
                let restCenter = CGPoint(x: center.x, y: center.y - radius)
                fillCircle(&context, at: restCenter, radius: lineWidth * 0.6, color: palette.overlay.opacity(0.12))
                fillCircle(&context, at: restCenter, radius: lineWidth * 0.34, color: palette.muted)
            }

            // 7. Busy lane (glow + core).
            let busyRadius = radius - lineWidth * Self.busyRadiusFactor
            for arc in busyArcs {
                let color = palette.busyColor(Int(arc.colorIndex))
                var lane = Path()
                lane.addArc(center: center, radius: busyRadius, startAngle: .degrees(arc.startAngleDegrees), endAngle: .degrees(arc.startAngleDegrees + arc.sweepDegrees), clockwise: false)
                context.stroke(lane, with: .color(color.opacity(0.16)), style: StrokeStyle(lineWidth: lineWidth * 0.7, lineCap: .round))
                context.stroke(lane, with: .color(color.opacity(0.92)), style: StrokeStyle(lineWidth: lineWidth * 0.42, lineCap: .round))
            }
        }
    }

    private func stroke(_ context: inout GraphicsContext, circleAt center: CGPoint, radius: CGFloat, color: Color) {
        var path = Path()
        path.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
        context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
    }

    private func fillCircle(_ context: inout GraphicsContext, at center: CGPoint, radius: CGFloat, color: Color) {
        let rect = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
        context.fill(Path(ellipseIn: rect), with: .color(color))
    }
}
```

Note on the gradient: `.conicGradient` inside the rotated `drawLayer` reproduces the JVM's rotated `sweepGradient`. If the seam still reads wrong in the smoke test, the spec's fallback is to drop the `drawLayer` rotation and offset the conic gradient's `angle` by `momentAngleDegrees` instead — same visual, different anchor. Adjust only if the smoke test shows a mid-arc band.

- [ ] **Step 4: Native build (compile check before wiring the callers)**

Run: `./gradlew :core:compileKotlinMacosArm64` then `cd macos && xcodegen generate && cd - && xcodebuild -project macos/DayView.xcodeproj -scheme DayView -configuration Debug -derivedDataPath macos/build build 2>&1 | tail -3`
Expected: `** BUILD SUCCEEDED **`. (The callers still pass the OLD `DayRingCanvas` signature until Task 3 — this build will FAIL at the call sites. That is expected; the purpose here is to catch syntax errors inside the new `DayRingCanvas`/`DayViewPalette` themselves. If the only errors are "missing argument" at `RingView`/`MiniView` call sites, proceed; fix any error INSIDE the two new/changed files.)

- [ ] **Step 5: Commit**

```bash
git add macos/DayView/DayViewPalette.swift macos/DayView/DayRingCanvas.swift
git rm macos/DayView/BusyPalette.swift 2>/dev/null; git add -A macos/DayView/BusyPalette.swift 2>/dev/null || true
git commit -m "feat(macos): DayView palette and fully layered dial"
```

(If `git rm` in Step 2 already staged the deletion, the commit picks it up; the redundant line is harmless.)

---

## Task 3: Interior countdown, glow background, and panel cards

**Files:**
- Modify: `macos/DayView/RingView.swift`
- Modify: `macos/DayView/MiniView.swift`

**Interfaces:**
- Consumes: `DayRingCanvas(momentAngleDegrees:remainingRatio:isFinished:hasStarted:hasGoal:busyArcs:)` and its statics; `DayViewPalette`; snapshot fields incl. `hasStarted`, `goalTitle`, `goalHasDeadline`.

- [ ] **Step 1: `RingView` — dial with interior countdown + hover; hit-test uses statics**

In `macos/DayView/RingView.swift`, replace the `ringSection` computed property with:

```swift
    private var ringSection: some View {
        GeometryReader { proxy in
            ZStack {
                DayRingCanvas(
                    momentAngleDegrees: model.snapshot.momentAngleDegrees,
                    remainingRatio: model.snapshot.remainingRatio,
                    isFinished: model.snapshot.isFinished,
                    hasStarted: model.snapshot.hasStarted,
                    hasGoal: !model.snapshot.goalTitle.isEmpty || model.snapshot.goalHasDeadline,
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
                VStack(spacing: 2) {
                    Text(model.snapshot.dayStatus)
                        .font(.system(size: 40, weight: .light, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(palette.cloud)
                    if !model.snapshot.secondsLabel.isEmpty {
                        Text(model.snapshot.secondsLabel)
                            .font(.caption).monospacedDigit().foregroundStyle(palette.muted)
                    }
                    if !model.snapshot.netTimeLabel.isEmpty {
                        Text(model.snapshot.netTimeLabel)
                            .font(.caption).monospacedDigit().foregroundStyle(palette.muted)
                    }
                }
                if let hover = hoveredBusy {
                    Text(hover.label)
                        .font(.caption)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                        .position(x: hover.position.x + 12, y: hover.position.y - 28)
                        .allowsHitTesting(false)
                }
            }
        }
        .frame(height: 300)
    }
```

2. Add a `palette` helper and update `busyArcHit` to use the statics. Add after the `@State` properties:

```swift
    @Environment(\.colorScheme) private var colorScheme
    private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }
```

3. In `busyArcHit`, change the two hardcoded lines
`let inset: CGFloat = 40` / `let lineWidth: CGFloat = 18` to:

```swift
        let inset = DayRingCanvas.defaultInset
        let lineWidth = DayRingCanvas.defaultLineWidth
```

- [ ] **Step 2: `RingView` — glow background + panel cards**

1. Wrap the `ScrollView { ... }` body: change the outer `var body: some View { ScrollView { ... } ... }` so the `ScrollView` gets a full-bleed glow background. Add this modifier to the `ScrollView` (after its content closure, alongside any existing modifiers):

```swift
        .background(
            RadialGradient(
                gradient: Gradient(colors: [palette.glow, palette.ink]),
                center: .center, startRadius: 0, endRadius: 500
            )
            .ignoresSafeArea()
        )
```

2. Replace the two `GroupBox` cards (`focusSection`, `goalSection`) with the DayView panel style. Define a reusable modifier at the bottom of the file (top-level):

```swift
private struct DayViewPanel: ViewModifier {
    let palette: DayViewPalette
    func body(content: Content) -> some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.panel, in: RoundedRectangle(cornerRadius: 15))
            .overlay(RoundedRectangle(cornerRadius: 15).stroke(palette.overlay.opacity(0.06), lineWidth: 1))
    }
}

extension View {
    func dayViewPanel(_ palette: DayViewPalette) -> some View { modifier(DayViewPanel(palette: palette)) }
}
```

Then change `focusSection` and `goalSection` from `GroupBox("Focus") { VStack(...) { ... } }` to a titled panel: a leading VStack whose first child is a small-caps header, wrapped in `.dayViewPanel(palette)`. Concretely, replace the `GroupBox("Focus") {` wrapper with:

```swift
        VStack(alignment: .leading, spacing: 12) {
            Text("FOCUS")
                .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.amber)
            // ... existing focus VStack children ...
        }
        .dayViewPanel(palette)
```

and likewise `goalSection` with header text `"LONG-TERM GOAL"` in `palette.mint`. Keep every inner control and the `closureSection`/`FocusClosureButtons` exactly as they are; only the wrapper and header change. Tint the primary controls: `Button("Start focus")` gets `.tint(palette.amber)`; `Button("Relaunch")` `.tint(palette.amber)`; `Button("Stop focus")` `.tint(palette.red)`. (The closure buttons already carry green/orange/plain tints.)

- [ ] **Step 3: `MiniView` — same treatment, mini sizing**

1. Replace the ring block in `MiniView.body` (the `VStack(spacing: 4) { DayRingCanvas(...); Text(dayStatus); secondsLabel; netTimeLabel }`) with a ZStack dial + interior text mirroring RingView but at mini scale (dial fills, countdown ~24 pt):

```swift
                    ZStack {
                        DayRingCanvas(
                            momentAngleDegrees: model.snapshot.momentAngleDegrees,
                            remainingRatio: model.snapshot.remainingRatio,
                            isFinished: model.snapshot.isFinished,
                            hasStarted: model.snapshot.hasStarted,
                            hasGoal: !model.snapshot.goalTitle.isEmpty || model.snapshot.goalHasDeadline,
                            busyArcs: model.snapshot.busyArcs,
                            lineWidth: 12,
                            inset: 20
                        )
                        VStack(spacing: 2) {
                            Text(model.snapshot.dayStatus)
                                .font(.system(size: 24, weight: .light, design: .rounded))
                                .monospacedDigit()
                                .foregroundStyle(palette.cloud)
                            if !model.snapshot.secondsLabel.isEmpty {
                                Text(model.snapshot.secondsLabel).font(.caption2).monospacedDigit().foregroundStyle(palette.muted)
                            }
                            if !model.snapshot.netTimeLabel.isEmpty {
                                Text(model.snapshot.netTimeLabel).font(.caption2).monospacedDigit().foregroundStyle(palette.muted)
                            }
                        }
                    }
                    .frame(maxHeight: .infinity)
```

2. Add the palette helper (mirror RingView Step 1.2): `@Environment(\.colorScheme) private var colorScheme` + `private var palette: DayViewPalette { DayViewPalette.current(for: colorScheme) }`.

3. Glow background: add to the outer container (the `GeometryReader`'s `ZStack` or `MiniView.body`'s root) the same `.background(RadialGradient(glow → ink)...ignoresSafeArea())` as RingView Step 2.1.

4. Cards: replace the mini's `.background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))` on `goalCard` and `focusCard` with `.dayViewPanel(palette)` (reuse the same extension — it's file-visible if both files are in the target; if not, move `DayViewPanel`/`dayViewPanel` into `DayViewPalette.swift` so both windows share it). Tint the mini's Start focus / Relaunch amber, Stop red, as in RingView.

**Decision for the implementer:** put the `DayViewPanel` modifier + `dayViewPanel` extension in `DayViewPalette.swift` (not `RingView.swift`) so both `RingView` and `MiniView` use it without duplication.

- [ ] **Step 4: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches with the ink/glow background, the layered dial, the countdown inside it, and panel cards.

- [ ] **Step 5: Verify what the environment allows, report the rest as manual**

Confirm automatically: build, launch, process alive (`pgrep -f 'Debug/DayView.app'`). The **manual smoke test** (both windows, light AND dark — toggle via Settings → Appearance) — report as not-verified-in-sandbox:

1. Ink/glow radial background; panel cards with amber "FOCUS" / mint "LONG-TERM GOAL" headers.
2. Dial: 24 hour ticks (majors longer), mint gradient sweep brightest at the amber moment marker (with the white highlight); accent turns amber when remaining < 20% (temporarily set the day end ~1 h out in Settings); finished day → calm neutral ring + resting marker at 12 o'clock (set the end in the past).
3. Before-start → full uniform ring, no marker (set the start later than now).
4. Countdown + seconds + net lines centered inside the dial in both windows.
5. Busy arcs render as glow+core; hover labels + 5° margin still work.
6. Goal halo appears when a goal title/deadline is set, gone when cleared.
7. Settings window and the menu-bar dropdown look unchanged (system-styled).
8. The mini at its 200×300 minimum: interior text fits inside the dial.

Close afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 6: Commit**

```bash
git add macos/DayView/RingView.swift macos/DayView/MiniView.swift macos/DayView/DayViewPalette.swift
git commit -m "feat(macos): DayView-styled product windows with the countdown inside the dial"
```

---

## Self-Review Notes

- **Spec coverage:** `hasStarted` → Task 1; palette (dark+light, verbatim) + `BusyPalette` deletion → Task 2 Steps 1–2; the seven dial layers with JVM constants → Task 2 Step 3 (track/halo/ticks/sweep-gradient/marker/finished/glow+core busy); interior countdown → Task 3 Steps 1/3; glow background → Task 3 Steps 2/3; panel cards + section-color headers + control tints → Task 3 Steps 2/3; statics closing the hardcoded-40/18 parking-lot note → Task 2 statics + Task 3 Step 1.3; glow+core closing the heavy-lane note → Task 2 Step 3 layer 7; Settings/dropdown untouched → not in any task's file set; smoke list → Task 3 Step 5.
- **Type consistency:** `DayRingCanvas(momentAngleDegrees:remainingRatio:isFinished:hasStarted:hasGoal:busyArcs:lineWidth:inset:)` identical across the two call sites and the statics used by `busyArcHit`; `DayViewPalette.current(for:)`/`busyColor(_:)` consistent; `hasStarted`/`remainingRatio`/`isFinished` all pre-existing snapshot fields except `hasStarted` (Task 1).
- **No placeholders:** every step carries complete code or an exact edit; Task 2 Step 4 explicitly scopes the expected transient call-site failures.
- **YAGNI:** no focus/detour lanes, no scrub marker, no Settings restyle, no custom fonts, no new animations.
- **Known nuance:** the interior renders the existing `dayStatus` string large (not the JVM's stacked H/h/MM numerals) — a deliberate Non-Goal; the composition is faithful, the numeral micro-layout is deferred.
