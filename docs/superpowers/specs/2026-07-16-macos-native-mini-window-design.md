# macOS Native — Mini always-on-top window (Path B, Phase 5b)

## Context

The native SwiftUI macOS app (Path B; phases 1–5a merged) is a menu-bar-resident app with a
single main window (`RingView`: ring, focus card with the closure ritual, goal editing). The
Compose/JVM app additionally offers a **mini window**: a compact, always-on-top companion
(default 360×520, minimum 200×300, resizable) showing the countdown ring, a goal card, and a
focus card, swapped with the main window as two mutually exclusive modes ("mini mode" vs
"full mode") from the tray and a header control.

Phase 5b brings that mini window to the native app at **full parity**, reusing the shared
`TodayModel`/`DayViewSession` and inheriting the Phase 5a closure ritual. It is Swift-only:
no `:core`, controller, bridge, or persistence changes.

## Goals

- A compact always-on-top window: ring + countdown, goal card, full focus card (start via an
  intention sheet, live clock, Stop/Relaunch, the three-outcome closure ritual during break).
- Mutually exclusive with the main window, mirroring the JVM: showing one closes the other.
- Three entry points: a menu-bar toggle item, a compact-mode control in the main window, and
  an expand glyph in the mini back to the main window.
- One shared `TodayModel` — the mini observes the same session as the main window and the
  menu bar.

## Non-Goals (deferred or out of scope)

- Menu-bar icon asset; accessory/Dock-hidden mode.
- Translucency/opacity controls; remembering mini position/size beyond what macOS restores.
- Net-time/detour arcs in the mini (the native app has no calendar data yet).
- i18n (native UI copy stays hardcoded English, matching the existing screens).
- Any `:core`/Kotlin change.

## Decisions (from brainstorming)

1. **Full parity** with the JVM mini (ring + goal card + focus card incl. closure ritual),
   not a read-only glanceable.
2. **Entry points: menu bar + main-window control** (plus the expand glyph inside the mini),
   mirroring the JVM's two entry points.
3. **Mutually exclusive** with the main window, exactly the JVM model.
4. **Deployment target raised to macOS 15.0** to use the native window APIs —
   `.windowLevel(.floating)` (15+) and `@Environment(\.dismissWindow)` (14+) — instead of
   NSWindow accessor workarounds.

## Consequence of the macOS 15 floor (recorded deliberately)

`macos/project.yml`'s `deploymentTarget.macOS` moves `"13.0"` → `"15.0"`. This affects the
**native app only**: the shipping Compose/JVM `.dmg` remains macOS 13+ and is unaffected.
It does mean the eventual native packaging/CI cutover would drop macOS 13/14 unless the
floor is revisited then. The README's "Native macOS app (SwiftUI, experimental)" section
gains one line stating the native app requires macOS 15+.

## Architecture

### Scenes (`DayViewApp`)

A second singleton window scene joins the Phase 4 pair:

```swift
Window("DayView Mini", id: "mini") {
    MiniView(model: model, windows: windows)
        .frame(minWidth: 200, minHeight: 300)
}
.windowLevel(.floating)
.defaultSize(width: 360, height: 520)
```

The main `Window(id: "main")` and the `MenuBarExtra` are unchanged in kind; both window
roots additionally report their visibility (below). The shared `@StateObject` `TodayModel`
is passed to `MiniView` exactly as it is to `RingView` — never a second instance.

### Window visibility & exclusivity (`WindowVisibility`)

SwiftUI cannot query whether a window is open, and the menu-bar toggle needs to know. A
small observable object tracks it:

```swift
final class WindowVisibility: ObservableObject {
    @Published var isMainOpen = false
    @Published var isMiniOpen = false
}
```

Owned by `DayViewApp` as a second `@StateObject` and passed to `RingView`'s window root,
`MiniView`, and `MenuBarContent`. Each window's root view sets its flag in
`.onAppear`/`.onDisappear`.

The exclusivity swaps are two compositions of `openWindow`/`dismissWindow`:

- **switch to mini:** `openWindow(id: "mini")` then `dismissWindow(id: "main")`.
- **switch to main:** `openWindow(id: "main")` then `dismissWindow(id: "mini")`.

State machine (JVM parity):

```
[main open]  — menu "Show mini window" / main-window compact button →  [mini open]
[mini open]  — expand glyph / menu "Open full window" →                [main open]
close mini (⌘W/red)  → menu bar only (Phase 4 residency unchanged)
close main (⌘W/red)  → menu bar only (Phase 4 residency unchanged)
"Quit DayView"       → terminates
```

### Menu bar (`MenuBarContent`)

The "Open DayView" button is replaced by two coherent items driven by `WindowVisibility`:

- When the mini is open: **"Open full window"** → switch to main.
- Otherwise: **"Open DayView"** (opens/foregrounds main; unchanged behaviour) and
  **"Show mini window"** → switch to mini.

Status lines and "Quit DayView" are unchanged.

### Main window control (`RingView`)

A small compact-mode button overlaid top-trailing in the main window (mirroring the JVM
header affordance) → switch to mini. Implemented at the window-root level (a `ZStack`
overlay around `RingView`'s scroll view or in `DayViewApp`'s main scene content) so
`RingView`'s inner layout is untouched apart from the overlay.

### Shared ring drawing (`DayRingCanvas`)

The ring `Canvas` block currently inline in `RingView.ringSection` is extracted into a
shared view so main and mini render identically without duplication:

```swift
struct DayRingCanvas: View {
    let momentAngleDegrees: Double
    var lineWidth: CGFloat = 18
    var inset: CGFloat = 40
    // Canvas drawing exactly as today: gray track circle + accent sweep from -90°.
}
```

`RingView` uses it with today's defaults (visually unchanged); `MiniView` uses a thinner
line and smaller inset. The countdown text stays outside the shared canvas (main keeps its
40 pt style; the mini uses a smaller size).

### Mini content (`MiniView`)

One new file, observing the shared model:

- **Ring + countdown** — `DayRingCanvas` filling the available vertical space, `dayStatus`
  text beneath in a compact size.
- **Goal card** — `goalTitle` (or a muted "No goal yet" when blank) and, when
  `goalHasDeadline`, "\(goalHoursRemaining)h left". Hidden when the window height is under
  **400 pt** (the JVM's `showGoalInMiniWindow` threshold at font scale 1; the native app has
  no font-scale preference, so the fixed value is exact parity) via a
  `GeometryReader`/frame check.
- **Focus card**, keyed off `pomodoroStatus` with the same semantics as `RingView`:
  - IDLE → **"Start focus"** button opening an **intention sheet** (TextField prefilled from
    `snapshot.focusIntention`, Cancel/Start, Start disabled when the text is empty) →
    `startFocus(intention:)`.
  - ACTIVE → intention + live `pomodoroClock` + **Stop** (`stopFocus`).
  - BREAK → intention + break clock + **Relaunch** (`startFocus(intention:
    snapshot.focusIntention)`) + **Stop** + the three closure buttons —
    **Completed**/**Progressed**/**Resume later** → `closeFocus("COMPLETED" | "PROGRESSED" |
    "TO_RESUME")` — exactly the Phase 5a calls.
- **Expand glyph** — top-trailing overlay → switch to main.

The mini has no intention text field outside the sheet and no goal editing — display plus
the focus actions, like the JVM mini.

## Data flow

```
model.$snapshot (1 Hz, shared DayViewSession)
  -> RingView (main), MiniView (mini), MenuBarExtra label + MenuBarContent — same object
window roots' onAppear/onDisappear -> WindowVisibility flags
menu toggle / main compact button / mini expand glyph
  -> openWindow(id:) + dismissWindow(id:) swaps (mutually exclusive)
mini focus actions -> TodayModel.startFocus/stopFocus/closeFocus (existing methods)
```

## Testing / done criteria

`:core` is untouched, so there are no new Kotlin tests. Verified by building and driving
the app (`./gradlew :core:runMacNative`):

- The mini opens from the menu bar item and from the main-window compact button; the main
  window closes when it does (and vice versa via the expand glyph / "Open full window").
- The mini floats above other applications' windows.
- The mini shows the live ring + countdown ticking; the goal card appears when a goal/deadline
  is set and hides below the height threshold.
- Focus from the mini: start via the intention sheet; live clock; Stop; during break,
  Relaunch and the three closure outcomes behave as in the main window (shared state:
  the menu-bar title reflects every transition).
- Closing the mini leaves the app resident in the menu bar (Phase 4 behaviour intact).

*(As in earlier phases, the sandbox cannot drive GUI clicks — the build succeeding plus a
manual smoke test is the fallback; the plan lists the exact checklist and what can/cannot be
verified automatically.)*

## Risks

- **Window-visibility tracking** — `onAppear`/`onDisappear` are per-root-view, not true
  window lifecycle; ordering during a swap (open new, dismiss old) must not leave both
  flags false or both true at rest. Mitigation: flags set independently by each root; the
  menu label only needs eventual consistency on the next render.
- **`.windowLevel(.floating)` + `MenuBarExtra` interplay** — the floating level must not
  interfere with the dropdown; no known issue, verified in the smoke test.
- **Height-gated goal card** — the fixed 400 pt threshold matches the JVM at font scale 1;
  if the native app later gains a font-scale preference, the gate must scale with it (as
  `showGoalInMiniWindow` does).

## Roadmap after this phase (context only)

Native EventKit + net-time arcs (the busy lane, hover details), settings screen, focus
resume ritual and presence tracking, packaging/signing/CI cutover, macOS Widget.
