# macOS Native Mini Always-On-Top Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A compact always-on-top mini window (ring + countdown, display-only goal card, full focus card with the closure ritual), mutually exclusive with the main window, entered from the menu bar and a main-window control.

**Architecture:** Raise the native app's floor to macOS 15 to use `.windowLevel(.floating)` and `dismissWindow`; extract the ring `Canvas` into a shared `DayRingCanvas`; add a `Window(id: "mini")` scene hosting a new `MiniView` over the same shared `TodayModel`; a small `WindowVisibility` observable tracks which window is open so the menu-bar items can toggle, and every swap is `openWindow(id:)` + `dismissWindow(id:)`.

**Tech Stack:** SwiftUI (macOS 15+), the existing `DayViewKit` XCFramework bridge (untouched), XcodeGen.

## Global Constraints

- Swift + `macos/project.yml` + `README.md` only. NO `:core`, Kotlin, controller, bridge, or persistence changes.
- Deployment target: `macos/project.yml` `deploymentTarget.macOS` becomes exactly `"15.0"` (Task 1) — `.windowLevel(.floating)` is macOS 15+, `@Environment(\.dismissWindow)` is 14+.
- One shared `TodayModel` — constructed exactly once (`DayViewApp`'s `@StateObject`); never construct a second one.
- Window ids are exactly `"main"` and `"mini"`; every swap opens one and dismisses the other (mutual exclusivity).
- Closure outcome strings are exactly `"COMPLETED"`, `"PROGRESSED"`, `"TO_RESUME"` (the Phase 5a bridge contract).
- Mini window: default size 360×520, content minimum 200×300, resizable; goal card hidden when window height < 400 pt.
- Native UI copy is hardcoded English, matching the existing screens.
- Commit messages: English, imperative, change-only; no Claude/Anthropic/AI references, no Co-Authored-By. Commits succeed unsigned.
- Build/run: `./gradlew :core:runMacNative` (syncs the XCFramework, regenerates the Xcode project via xcodegen — new Swift files are picked up automatically by the `sources: - path: DayView` glob — builds, launches). Headless GUI clicking is blocked in this environment: interactive checks are a manual smoke test; report exactly what was and wasn't verified.
- **Before Task 1:** create the working branch from the current commit: `git checkout -b claude/macos-native-mini-window`.

## File map

- Modify: `macos/project.yml` — deployment target `"13.0"` → `"15.0"`.
- Modify: `README.md` — one line in the native-app section (macOS 15+ requirement).
- Create: `macos/DayView/DayRingCanvas.swift` — the shared ring drawing.
- Modify: `macos/DayView/RingView.swift` — `ringSection` uses `DayRingCanvas` (visually unchanged).
- Create: `macos/DayView/WindowVisibility.swift` — observable open/closed flags.
- Create: `macos/DayView/MiniView.swift` — the mini window content (ring, goal card, focus card, intention sheet, expand glyph).
- Modify: `macos/DayView/DayViewApp.swift` — mini scene, `WindowVisibility`, main-window compact button, visibility reporting.
- Modify: `macos/DayView/MenuBarContent.swift` — visibility-driven window items.

---

## Task 1: macOS 15 floor + shared ring extraction

Prerequisites with a "nothing visibly changes" deliverable: raise the deployment target, note it in the README, and extract the ring drawing into `DayRingCanvas` so Task 2's mini can reuse it. The app must build and render exactly as before.

**Files:**
- Modify: `macos/project.yml`
- Modify: `README.md`
- Create: `macos/DayView/DayRingCanvas.swift`
- Modify: `macos/DayView/RingView.swift`

**Interfaces:**
- Produces: `DayRingCanvas(momentAngleDegrees: Double, lineWidth: CGFloat = 18, inset: CGFloat = 40)` — a `View` drawing the gray track + accent sweep; Task 2's `MiniView` calls it as `DayRingCanvas(momentAngleDegrees: ..., lineWidth: 12, inset: 20)`. Also: the macOS 15 floor Task 2's scene modifiers require.

- [ ] **Step 1: Raise the deployment target**

In `macos/project.yml`, change:

```yaml
  deploymentTarget:
    macOS: "13.0"
```

to:

```yaml
  deploymentTarget:
    macOS: "15.0"
```

- [ ] **Step 2: Note the floor in the README**

In `README.md`, in the "### Native macOS app (SwiftUI, experimental)" section, the first paragraph ends with "…this native app is not yet feature-complete." Append this sentence to that same paragraph:

```
The native app requires macOS 15 or later; the Compose/JVM release above remains the macOS 13+ build.
```

- [ ] **Step 3: Create the shared ring view**

Create `macos/DayView/DayRingCanvas.swift`:

```swift
import SwiftUI

/// The countdown ring shared by the main window and the mini window: a gray full-circle
/// track plus the accent remaining-time sweep anchored at 12 o'clock. Drawing only —
/// the countdown text and layout stay with the callers.
struct DayRingCanvas: View {
    let momentAngleDegrees: Double
    var lineWidth: CGFloat = 18
    var inset: CGFloat = 40

    var body: some View {
        Canvas { context, size in
            let side = min(size.width, size.height) - inset * 2
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = max(side / 2, 1)
            var track = Path()
            track.addArc(center: center, radius: radius, startAngle: .degrees(0), endAngle: .degrees(360), clockwise: false)
            context.stroke(track, with: .color(.gray.opacity(0.2)), lineWidth: lineWidth)
            var sweep = Path()
            sweep.addArc(center: center, radius: radius, startAngle: .degrees(-90), endAngle: .degrees(momentAngleDegrees), clockwise: false)
            context.stroke(sweep, with: .color(.accentColor), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
        }
    }
}
```

- [ ] **Step 4: Use it from `RingView`**

In `macos/DayView/RingView.swift`, replace the entire `ringSection` computed property (the `VStack` whose first child is the `Canvas { context, size in ... }` block) with:

```swift
    private var ringSection: some View {
        VStack(spacing: 8) {
            DayRingCanvas(momentAngleDegrees: model.snapshot.momentAngleDegrees)
                .frame(height: 260)
            Text(model.snapshot.dayStatus)
                .font(.system(size: 40, weight: .semibold, design: .rounded))
                .monospacedDigit()
        }
    }
```

(The default `lineWidth: 18` / `inset: 40` reproduce today's drawing exactly. Nothing else in `RingView` changes in this task.)

- [ ] **Step 5: Build and launch — visually unchanged**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches with the ring, Focus, and Goal sections rendering exactly as before. Close it afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 6: Commit**

```bash
git add macos/project.yml README.md macos/DayView/DayRingCanvas.swift macos/DayView/RingView.swift
git commit -m "refactor(macos): extract the shared countdown ring and require macOS 15"
```

---

## Task 2: Mini window, exclusivity, and entry points

The feature itself: the `MiniView` content, the floating `Window(id: "mini")` scene, the `WindowVisibility` tracker, and all three entry points (menu-bar toggle, main-window compact button, mini expand glyph), each swap mutually exclusive.

**Files:**
- Create: `macos/DayView/WindowVisibility.swift`
- Create: `macos/DayView/MiniView.swift`
- Modify: `macos/DayView/DayViewApp.swift`
- Modify: `macos/DayView/MenuBarContent.swift`

**Interfaces:**
- Consumes: `DayRingCanvas(momentAngleDegrees:lineWidth:inset:)` from Task 1; existing `TodayModel` methods `startFocus(intention:)`, `stopFocus()`, `closeFocus(_:)` (Phase 5a); `TodaySnapshot` fields `momentAngleDegrees`, `dayStatus`, `pomodoroStatus`, `pomodoroClock`, `focusIntention`, `goalTitle`, `goalHasDeadline`, `goalHoursRemaining`.
- Produces: `WindowVisibility` (`isMainOpen`/`isMiniOpen`, both `@Published Bool`), `MiniView(model: TodayModel)`, window id `"mini"`.

- [ ] **Step 1: Create the visibility tracker**

Create `macos/DayView/WindowVisibility.swift`:

```swift
import SwiftUI

/// Tracks which of the two windows is open. SwiftUI cannot query window visibility, and
/// the menu-bar items change with it; each window's root content reports itself via
/// onAppear/onDisappear (set in DayViewApp's scenes).
final class WindowVisibility: ObservableObject {
    @Published var isMainOpen = false
    @Published var isMiniOpen = false
}
```

- [ ] **Step 2: Create the mini window content**

Create `macos/DayView/MiniView.swift`:

```swift
import SwiftUI
import DayViewKit

/// Compact always-on-top companion: ring + countdown, a display-only goal card, and the
/// full focus card (intention sheet, live clock, stop/relaunch, closure ritual). Mirrors
/// the JVM mini window; observes the same TodayModel as the main window and menu bar.
struct MiniView: View {
    @ObservedObject var model: TodayModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow
    @State private var showIntentionSheet = false
    @State private var draftIntention = ""

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .topTrailing) {
                VStack(spacing: 12) {
                    VStack(spacing: 4) {
                        DayRingCanvas(momentAngleDegrees: model.snapshot.momentAngleDegrees, lineWidth: 12, inset: 20)
                            .frame(maxHeight: .infinity)
                        Text(model.snapshot.dayStatus)
                            .font(.system(size: 24, weight: .semibold, design: .rounded))
                            .monospacedDigit()
                    }
                    // Height-gated like the JVM mini (showGoalInMiniWindow: 400 at font scale 1).
                    if proxy.size.height >= 400 {
                        goalCard
                    }
                    focusCard
                }
                .padding(16)
                // Expand back to the full window (mirrors the JVM's expand glyph).
                Button {
                    openWindow(id: "main")
                    dismissWindow(id: "mini")
                } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                }
                .buttonStyle(.borderless)
                .help("Open the full window")
                .padding(8)
            }
        }
        .sheet(isPresented: $showIntentionSheet) { intentionSheet }
    }

    private var goalCard: some View {
        HStack {
            Text(model.snapshot.goalTitle.isEmpty ? "No goal yet" : model.snapshot.goalTitle)
                .foregroundStyle(model.snapshot.goalTitle.isEmpty ? Color.secondary : Color.primary)
                .lineLimit(1)
            Spacer()
            if model.snapshot.goalHasDeadline {
                Text("\(model.snapshot.goalHoursRemaining)h left")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
    }

    private var focusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch model.snapshot.pomodoroStatus {
            case "ACTIVE":
                HStack(spacing: 8) {
                    Text("Focus · \(model.snapshot.focusIntention)")
                        .lineLimit(1)
                    Spacer()
                    Text(model.snapshot.pomodoroClock).monospacedDigit()
                    Button("Stop") { model.stopFocus() }
                }
            case "BREAK":
                HStack(spacing: 8) {
                    Text("Break · \(model.snapshot.focusIntention)")
                        .lineLimit(1)
                    Spacer()
                    Text(model.snapshot.pomodoroClock).monospacedDigit()
                    // Relaunch the next session of the sequence, keeping the intention.
                    Button("Relaunch") { model.startFocus(intention: model.snapshot.focusIntention) }
                    Button("Stop") { model.stopFocus() }
                }
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
            default: // "IDLE"
                Button("Start focus") {
                    draftIntention = model.snapshot.focusIntention
                    showIntentionSheet = true
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
    }

    private var intentionSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("What are you focusing on?")
                .font(.headline)
            TextField("Focus intention", text: $draftIntention)
                .textFieldStyle(.roundedBorder)
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
        }
        .padding(20)
        .frame(width: 320)
    }
}
```

- [ ] **Step 3: Add the mini scene, visibility reporting, and the compact button**

Replace the entire contents of `macos/DayView/DayViewApp.swift` with:

```swift
import SwiftUI

@main
struct DayViewApp: App {
    @StateObject private var model = TodayModel()
    @StateObject private var windows = WindowVisibility()

    var body: some Scene {
        Window("DayView", id: "main") {
            MainWindowRoot(model: model, windows: windows)
        }
        MenuBarExtra(menuBarTitle) {
            MenuBarContent(model: model, windows: windows)
        }
        Window("DayView Mini", id: "mini") {
            MiniView(model: model)
                .frame(minWidth: 200, minHeight: 300)
                .onAppear { windows.isMiniOpen = true }
                .onDisappear { windows.isMiniOpen = false }
        }
        .windowLevel(.floating)
        .defaultSize(width: 360, height: 520)
    }

    // Live menu-bar readout: the focus countdown during a session, otherwise the day's
    // remaining-time headline. Recomputed whenever the model's snapshot ticks.
    private var menuBarTitle: String {
        switch model.snapshot.pomodoroStatus {
        case "ACTIVE", "BREAK": return model.snapshot.pomodoroClock
        default: return model.snapshot.dayStatus
        }
    }
}

/// Main-window root: RingView plus the compact-mode affordance (mirrors the JVM header
/// control) and this window's visibility reporting.
private struct MainWindowRoot: View {
    @ObservedObject var model: TodayModel
    @ObservedObject var windows: WindowVisibility
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        ZStack(alignment: .topTrailing) {
            RingView(model: model)
            Button {
                openWindow(id: "mini")
                dismissWindow(id: "main")
            } label: {
                Image(systemName: "arrow.down.right.and.arrow.up.left")
            }
            .buttonStyle(.borderless)
            .help("Switch to the mini window")
            .padding(10)
        }
        .frame(minWidth: 420, minHeight: 680)
        .onAppear { windows.isMainOpen = true }
        .onDisappear { windows.isMainOpen = false }
    }
}
```

- [ ] **Step 4: Visibility-driven menu-bar items**

Replace the entire contents of `macos/DayView/MenuBarContent.swift` with:

```swift
import SwiftUI
import AppKit
import DayViewKit

struct MenuBarContent: View {
    @ObservedObject var model: TodayModel
    @ObservedObject var windows: WindowVisibility
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        Text(model.snapshot.dayStatus)
        if let focusLine {
            Text(focusLine)
        }
        if model.snapshot.goalHasDeadline {
            Text("\(model.snapshot.goalHoursRemaining)h left")
        }
        Divider()
        if windows.isMiniOpen {
            Button("Open full window") {
                openWindow(id: "main")
                dismissWindow(id: "mini")
            }
        } else {
            Button("Open DayView") { openWindow(id: "main") }
            Button("Show mini window") {
                openWindow(id: "mini")
                dismissWindow(id: "main")
            }
        }
        Button("Quit DayView") { NSApplication.shared.terminate(nil) }
    }

    // Mirrors RingView.focusText: "Focus" during an active session, "Break" during
    // the pause (no intention on the break line), nothing when idle.
    private var focusLine: String? {
        let s = model.snapshot
        switch s.pomodoroStatus {
        case "ACTIVE": return "Focus · \(s.focusIntention) · \(s.pomodoroClock)"
        case "BREAK": return "Break · \(s.pomodoroClock)"
        default: return nil
        }
    }
}
```

- [ ] **Step 5: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **` (xcodegen picks up the three new Swift files automatically); the app launches showing the main window with the ring plus a small compact-mode button top-right. If `.windowLevel` or `dismissWindow` are flagged unavailable, verify Task 1's `deploymentTarget.macOS: "15.0"` landed and xcodegen regenerated the project.

- [ ] **Step 6: Verify what the environment allows, report the rest as manual**

Confirm automatically: build succeeded, app launches, process alive (`pgrep -f 'Debug/DayView.app'`). The interactive checks below are the **manual smoke test** — report them as not-verified-in-sandbox:

1. Menu bar → "Show mini window": the mini opens (360×520, ring + countdown + goal/focus cards) and the main window closes; the menu now shows "Open full window".
2. The mini floats above other apps' windows; resizing below 400 pt height hides the goal card; 200×300 is the minimum.
3. Mini expand glyph (and menu "Open full window"): main returns, mini closes; menu shows "Open DayView" + "Show mini window" again.
4. Main-window compact button (top-right): swaps to the mini.
5. Focus from the mini: "Start focus" opens the intention sheet (Start disabled when empty); ACTIVE shows intention + clock + Stop; BREAK shows Relaunch + Stop + Completed/Progressed/Resume later, all behaving as in the main window; the menu-bar title tracks every transition.
6. Closing the mini (⌘W/red button) leaves the app resident in the menu bar (Phase 4 behaviour intact).

Close the app afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 7: Commit**

```bash
git add macos/DayView/WindowVisibility.swift macos/DayView/MiniView.swift macos/DayView/DayViewApp.swift macos/DayView/MenuBarContent.swift
git commit -m "feat(macos): mini always-on-top window with exclusive window swapping"
```

---

## Self-Review Notes

- **Spec coverage:** macOS 15 floor + README note → Task 1 Steps 1–2; `DayRingCanvas` extraction (main visually unchanged) → Task 1 Steps 3–4; mini scene with `.windowLevel(.floating)`, 360×520 default, 200×300 minimum → Task 2 Step 3; `WindowVisibility` + onAppear/onDisappear reporting → Task 2 Steps 1/3; mutual exclusivity via `openWindow`+`dismissWindow` on every swap → Task 2 Steps 2–4; three entry points (menu toggle, main compact button, mini expand glyph) → Task 2 Steps 3–4 and `MiniView`; goal card display-only with 400 pt gate → `MiniView.goalCard`; focus card full parity incl. Phase 5a closure calls → `MiniView.focusCard`; intention sheet (prefilled, Start disabled when empty) → `MiniView.intentionSheet`; menu items ("Open full window" when mini open; "Open DayView" + "Show mini window" otherwise) → Task 2 Step 4; testing/done criteria + manual smoke list → Task 2 Step 6.
- **Type consistency:** `DayRingCanvas(momentAngleDegrees:lineWidth:inset:)` defined in Task 1, called with defaults in `RingView` and with `lineWidth: 12, inset: 20` in `MiniView`; `WindowVisibility.isMainOpen`/`isMiniOpen` used in `DayViewApp` and `MenuBarContent`; window ids `"main"`/`"mini"` consistent across all `Window(id:)`/`openWindow`/`dismissWindow` calls; `MenuBarContent(model:windows:)` matches the call in `DayViewApp`; closure strings match the Phase 5a bridge.
- **No placeholders:** every code step carries complete code; both modified Swift files are full-content replacements.
- **YAGNI:** no NSWindow accessors (15-floor decision), no opacity/position persistence, no goal editing in the mini, no i18n — all per the spec's non-goals. `isMainOpen` is tracked for symmetry and future use by the same mechanism that needs `isMiniOpen`; the menu only branches on `isMiniOpen`.
- **Note on `isMainOpen`:** strictly, only `isMiniOpen` drives UI today. Kept because the spec's architecture section defines both flags and the reporting costs two modifiers; if the reviewer flags it as YAGNI, dropping it is a two-line change that doesn't affect behaviour.
