# macOS Native Menu-bar-resident App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the native macOS app a menu-bar-resident app — a `MenuBarExtra` with live remaining-time / focus-clock text and a dropdown menu, where closing the window hides to the menu bar instead of quitting.

**Architecture:** Move `TodayModel` ownership up to `DayViewApp` (`@StateObject`) so the menu bar and the window share one `DayViewSession`; `RingView` receives it as `@ObservedObject`. Replace the `WindowGroup` with a singleton `Window(id: "main")` and add a `MenuBarExtra` whose title is derived from the snapshot and whose content is a `MenuBarContent` dropdown.

**Tech Stack:** SwiftUI (macOS 13+ `MenuBarExtra` / `Window` / `openWindow`), the existing `DayViewKit` XCFramework.

## Global Constraints

- Swift-only. NO `:core`, controller, bridge, or persistence changes — everything comes from the existing `TodaySnapshot`.
- macOS deployment target 13.0 (`MenuBarExtra`, singleton `Window`, `openWindow` are all 13.0+).
- Menu-bar title: `pomodoroClock` when `pomodoroStatus` is `"ACTIVE"`/`"BREAK"`, else `dayStatus`.
- One shared `TodayModel` — never construct a second one (a second `DayViewSession` would double-tick).
- No mini window, no menu-bar icon asset, no accessory/Dock-hidden mode (all deferred).
- Commit messages: English, imperative, change-only; no Claude/Anthropic/AI references, no Co-Authored-By.
- Commit signing is disabled locally in this worktree; commits succeed unsigned.
- Build/run via `./gradlew :core:runMacNative` (syncs the XCFramework, regenerates the Xcode project, builds, launches). Build-only: `cd macos && xcodegen generate && cd - && xcodebuild -project macos/DayView.xcodeproj -scheme DayView -configuration Debug -derivedDataPath macos/build build`.

## File map

- Modify: `macos/DayView/RingView.swift` — model becomes injected (`@ObservedObject`), not owned.
- Modify: `macos/DayView/DayViewApp.swift` — owns the `@StateObject` model; singleton `Window` + `MenuBarExtra`.
- Create: `macos/DayView/MenuBarContent.swift` — the dropdown menu view.
- `macos/DayView/TodayModel.swift` — unchanged (already a shareable `ObservableObject`).

---

## Task 1: Lift `TodayModel` ownership to `DayViewApp`

Move the single source of the model to the app scene so it can later be shared with the menu bar, without changing behaviour yet. Deliverable: the app builds and runs exactly as before, but the model is owned by `DayViewApp` and injected into `RingView`.

**Files:**
- Modify: `macos/DayView/RingView.swift`
- Modify: `macos/DayView/DayViewApp.swift`

**Interfaces:**
- Produces: `RingView(model: TodayModel)` — an initializer taking the model; `DayViewApp` owns `@StateObject private var model: TodayModel`.

- [ ] **Step 1: Make `RingView` receive the model instead of owning it**

In `macos/DayView/RingView.swift`, change the first stored property:

```swift
struct RingView: View {
    @ObservedObject var model: TodayModel
    @State private var intention: String = ""
    @State private var goalTitle: String = ""
    @State private var deadline: Date = Date()
    @State private var seeded = false
```

(Only that one line changes: `@StateObject private var model = TodayModel()` → `@ObservedObject var model: TodayModel`. Everything else in `RingView` is unchanged.)

- [ ] **Step 2: Own the model in `DayViewApp` and inject it**

Replace the contents of `macos/DayView/DayViewApp.swift` with:

```swift
import SwiftUI

@main
struct DayViewApp: App {
    @StateObject private var model = TodayModel()

    var body: some Scene {
        WindowGroup {
            RingView(model: model)
                .frame(minWidth: 420, minHeight: 680)
        }
    }
}
```

- [ ] **Step 3: Build and run — behaviour unchanged**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`, the app launches showing the ring + Focus/Goal sections exactly as before (the model is now App-owned, but the UI is identical). Close the app afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 4: Commit**

```bash
git add macos/DayView/RingView.swift macos/DayView/DayViewApp.swift
git commit -m "refactor(macos): lift TodayModel ownership to the app scene"
```

---

## Task 2: Add the menu-bar item and make the app menu-bar-resident

Add the `MenuBarExtra` (live text + dropdown) and convert the window to a reopenable singleton so closing it hides to the menu bar. Deliverable: a menu-bar-resident app whose menu-bar text is live and whose window reopens from the menu.

**Files:**
- Create: `macos/DayView/MenuBarContent.swift`
- Modify: `macos/DayView/DayViewApp.swift`

**Interfaces:**
- Consumes: `RingView(model:)` and `@StateObject model` from Task 1; `TodaySnapshot` fields `dayStatus`, `pomodoroStatus`, `pomodoroClock`, `focusIntention`, `goalHasDeadline`, `goalHoursRemaining`.
- Produces: `MenuBarContent(model: TodayModel)`; a window with id `"main"`.

- [ ] **Step 1: Create the dropdown menu view**

Create `macos/DayView/MenuBarContent.swift`:

```swift
import SwiftUI
import AppKit
import DayViewKit

struct MenuBarContent: View {
    @ObservedObject var model: TodayModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Text(model.snapshot.dayStatus)
        if isFocusing {
            Text("Focus · \(model.snapshot.focusIntention) · \(model.snapshot.pomodoroClock)")
        }
        if model.snapshot.goalHasDeadline {
            Text("\(model.snapshot.goalHoursRemaining)h left")
        }
        Divider()
        Button("Open DayView") { openWindow(id: "main") }
        Button("Quit DayView") { NSApplication.shared.terminate(nil) }
    }

    private var isFocusing: Bool {
        model.snapshot.pomodoroStatus == "ACTIVE" || model.snapshot.pomodoroStatus == "BREAK"
    }
}
```

- [ ] **Step 2: Convert to a singleton window + add the menu-bar scene**

Replace the contents of `macos/DayView/DayViewApp.swift` with:

```swift
import SwiftUI

@main
struct DayViewApp: App {
    @StateObject private var model = TodayModel()

    var body: some Scene {
        Window("DayView", id: "main") {
            RingView(model: model)
                .frame(minWidth: 420, minHeight: 680)
        }
        MenuBarExtra(menuBarTitle) {
            MenuBarContent(model: model)
        }
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
```

- [ ] **Step 3: Build**

Run: `cd macos && xcodegen generate && cd - && xcodebuild -project macos/DayView.xcodeproj -scheme DayView -configuration Debug -derivedDataPath macos/build build`
Expected: `** BUILD SUCCEEDED **`. If `MenuBarExtra`/`Window`/`openWindow` are flagged as unavailable, confirm `macos/project.yml`'s `deploymentTarget.macOS` is `"13.0"` (it is) — these APIs are macOS 13.0+.

- [ ] **Step 4: Launch and verify the menu-bar-resident behaviour**

Run: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true; open macos/build/Build/Products/Debug/DayView.app`; wait ~3s.

Verify (screenshot to the scratchpad and Read it where the environment permits; otherwise note what is blocked):
1. A menu-bar item shows the remaining-time text (e.g. `5h 09m`).
2. Its dropdown shows the day-status line, an "Open DayView" item, and a "Quit DayView" item.
3. Close the main window (red button or ⌘W) — the app process **stays alive** (`pgrep -f 'Debug/DayView.app'` still returns a PID) and the menu-bar item remains.
4. "Open DayView" (via the menu, or re-`open` the app) brings the window back.

Because GUI clicks / screen recording may be blocked (as in prior phases), at minimum confirm: build succeeded, the app launches, and after closing the window the process is still alive (`pgrep`). Report exactly what was and wasn't visually verified.

Close afterward: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 5: Commit**

```bash
git add macos/DayView/MenuBarContent.swift macos/DayView/DayViewApp.swift
git commit -m "feat(macos): menu-bar-resident app with live remaining-time readout"
```

---

## Self-Review Notes

- **Spec coverage:** shared model at app level → Task 1; `MenuBarExtra` live text + `menuBarTitle` rule → Task 2 Step 2; dropdown status lines + Open + Quit → Task 2 Step 1; singleton `Window(id:"main")` + resident behaviour → Task 2 Steps 2/4. Done criteria (menu-bar text ticks, close-doesn't-quit, reopen, focus switches the text, shared state) map to Task 2 Step 4.
- **The resident-lifecycle assumption:** with a `MenuBarExtra` scene present, a SwiftUI app is not terminated when its last window closes — the menu-bar scene keeps the process alive. Task 2 Step 4 verifies this directly (`pgrep` after closing the window). If a future macOS version changes this, the fallback is an `NSApplicationDelegateAdaptor` returning `false` from `applicationShouldTerminateAfterLastWindowClosed` — not needed now.
- **Type/name consistency:** `RingView(model:)` and `MenuBarContent(model:)` both take `TodayModel`; the window id `"main"` is used in both `Window(id:)` and `openWindow(id:)`; the `menuBarTitle` and `isFocusing` status-string checks (`"ACTIVE"`/`"BREAK"`) match `RingView`'s existing `focusText` switch and the `TodaySnapshot.pomodoroStatus` values from `:core`.
- **Placeholder scan:** no TBD/TODO; every code step carries complete Swift.
- **YAGNI:** `TodayModel` is untouched; no mini window, no icon asset, no accessory mode — all explicitly deferred per the spec.
