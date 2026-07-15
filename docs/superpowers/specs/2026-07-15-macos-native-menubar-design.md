# macOS Native — Menu-bar-resident app (Path B, Phase 4)

## Context

The native SwiftUI macOS app (Path B; phases 1–3 merged) is currently a single
`WindowGroup` hosting `RingView` (ring + focus/goal editing, native persistence). It quits
when its window closes. DayView on macOS is fundamentally a **menu-bar app** — the JVM
version lives in the menu bar: a tray status item whose dropdown shows focus/day/goal status
and window toggles, a live menu-bar focus countdown, window-close hides (doesn't quit), and a
mini always-on-top window.

Phase 4 gives the native app its signature macOS identity: a **menu-bar-resident app** with a
live menu-bar readout and a dropdown menu, where closing the window hides to the menu bar
instead of quitting. It uses only the existing `TodaySnapshot` — no new `:core` data. The
**mini window is deferred** to its own later increment.

## Goals

- A `MenuBarExtra` showing live text — remaining day time normally, the focus clock during a
  Focus session — that keeps the app alive with no windows open.
- A dropdown menu mirroring the JVM tray: day/focus/goal status lines + Open + Quit.
- Window-close hides to the menu bar; "Open DayView" reopens it.
- One shared `TodayModel`/`DayViewSession` across the menu bar and the window.

## Non-Goals (deferred)

- The mini always-on-top window (its own increment).
- A menu-bar *icon* asset (we use live text).
- Accessory / Dock-icon-hidden mode (the Dock icon stays, matching the JVM app).
- Any new `:core` data, controller change, or persistence change.

## Decisions (from brainstorming)

1. **Scope:** menu-bar item + resident behaviour only; mini window deferred.
2. **Menu-bar display:** live text — `dayStatus` normally, `pomodoroClock` during Focus.
3. **Dock icon kept** (regular app); accessory mode is a later refinement.

## Architecture

### Shared model at the App level

Today `RingView` owns `@StateObject private var model = TodayModel()`. Phase 4 moves that
ownership up to `DayViewApp` so both the menu bar and the window read the **same** model — one
`DayViewSession`, one controller, one persistence stream (two sessions would tick two
controllers against the same file):

```swift
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

    private var menuBarTitle: String { … } // derived from model.snapshot
}
```

`RingView` changes from owning the model (`@StateObject`) to receiving it
(`@ObservedObject var model: TodayModel`). No other `RingView` behaviour changes.

### Menu-bar label (live text)

The `MenuBarExtra` title string is derived from `model.snapshot`:

- `pomodoroStatus == "ACTIVE"` or `"BREAK"` → `pomodoroClock` (the focus countdown).
- otherwise → `dayStatus` (e.g. `"5h 09m"` or `"Day over"`).

It re-renders on every `@Published` snapshot tick (the model already ticks once a second). No
menu-bar icon asset is used.

### Dropdown menu (`.menu` style)

A `MenuBarContent` view rendering `model.snapshot` (mirrors the JVM tray order):

- **Status lines** (non-interactive `Text`): `dayStatus`; a focus line
  (`Focus · \(focusIntention) · \(pomodoroClock)`) shown only when a session is active; a goal
  line (`\(goalHoursRemaining)h left`) shown only when `goalHasDeadline`.
- `Divider()`.
- **`Button("Open DayView")`** → `@Environment(\.openWindow) private var openWindow` →
  `openWindow(id: "main")`.
- **`Button("Quit DayView")`** → `NSApplication.shared.terminate(nil)`.

### Window / resident behaviour

- The main window is a **singleton `Window(id: "main")`** (not `WindowGroup`), so there is
  exactly one and it can be reopened by id.
- Launch shows the window (matching the JVM app). Closing it removes the window; the
  `MenuBarExtra` scene keeps the process alive, so the app stays in the menu bar with its live
  text. "Open DayView" reopens/foregrounds it.
- The Dock icon stays (regular activation policy). No `LSUIElement` / `.accessory` change.

## Data flow

```
model.$snapshot (1 Hz tick, from DayViewSession)
  -> menuBarTitle recomputed -> MenuBarExtra label re-renders
  -> MenuBarContent status lines re-render
  -> RingView (window) re-renders (unchanged)
Menu "Open DayView" -> openWindow(id: "main")
Menu "Quit" -> NSApplication.shared.terminate
```

## Testing / done criteria

`:core` and the bridge are unchanged, so this is verified by building and driving the app
(`./gradlew :core:runMacNative`):

- The menu-bar item shows the remaining-time text and updates as time passes.
- Closing the main window **does not quit** — the app stays in the menu bar, text still live.
- "Open DayView" reopens the window and brings it to front.
- Starting a Focus switches the menu-bar text to the focus countdown; the dropdown shows the
  focus line; stopping returns it to remaining-time.
- "Quit DayView" terminates the app.
- The window and menu bar reflect the **same** state (one shared session): a goal/focus edit in
  the window updates the menu-bar text/lines live.

*(GUI/visual verification may be constrained by the environment's Accessibility/Screen-Recording
permissions, as in earlier phases; where automated checks are blocked, the build succeeding plus
a manual smoke test is the fallback — call out exactly what was and wasn't verified.)*

## Risks

- **Shared-model refactor** — moving `TodayModel` ownership to `DayViewApp` and switching
  `RingView` to `@ObservedObject`; a `@StateObject` on the `App` is created once and is the
  correct owner. Guard against accidentally creating a second `TodayModel` (which would spin a
  second `DayViewSession`).
- **Resident lifecycle** — confirm the process actually survives window close (the
  `MenuBarExtra` scene is what keeps it alive) and that "Open DayView" reliably re-shows the
  singleton window.

## Roadmap after this phase (context only)

The mini always-on-top window (compact ring); then the remaining today-screen panels (detours,
arcs — needing native EventKit/presence), settings, native platform integrations, packaging/CI
cutover, and the macOS Widget.
