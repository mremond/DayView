# macOS Native — Settings scene (Path B, Phase 6)

## Context

The native SwiftUI macOS app (Path B; phases 1–5b merged) still cannot change the one
preference the whole ring is computed from: the day start/end times. The Compose/JVM app
configures these (plus seconds display, sounds, theme, on-goal apps, net time, sync) on its
Settings screen. The `DayViewController` already has every setter — `setStartMinutes`,
`setEndMinutes`, `setShowSeconds`, `setThemeMode`, … — but none is exposed on the native
`DayViewSession` bridge, and `TodaySnapshot` does not carry the day window.

Phase 6 gives the native app a standard macOS **Settings scene** (app menu → Settings…, ⌘,)
covering the chosen first subset: **day window, seconds display, and appearance**. The other
JVM settings arrive with the features that need them (sounds with the sound player, net time
with EventKit, on-goal apps with presence tracking, sync with the sync subsystem).

## Goals

- Expose day-window, show-seconds, and theme-mode state and setters on the native bridge.
- A native `Settings` scene: start/end time pickers, a "Show seconds" toggle, an
  "Appearance" picker (System/Light/Dark), all autosaving.
- Render the seconds line under the countdown in both windows when enabled.
- Apply the chosen appearance to all native surfaces.
- **Consolidate presentation labels into `:core`** (see below): the seconds line, the
  focus status line, and the menu-bar title become Kotlin-computed snapshot fields —
  killing three Swift `switch`es on magic status strings and one verbatim duplicate — and
  the closure-button row becomes one shared Swift view.

## Non-Goals (deferred — each arrives with its feature)

- Sounds (needs a native sound player), net time + calendar selection (needs native
  EventKit), on-goal apps (needs presence tracking), sync (needs the native sync subsystem),
  font scale, launch-at-login, monochrome menu-bar icon, i18n.
- Any controller logic change — the existing setters and their clamping are used as-is.
- Seconds in `dayStatus` or the menu-bar title — the headline stays "5h 09m"; seconds are a
  secondary line, as on the JVM circle.

## Decisions (from brainstorming)

1. **Scope: day window + seconds + appearance** — including adding the seconds line to the
   native countdown (the JVM renders it as a small muted secondary line, not in the
   headline).
2. **Surface: SwiftUI `Settings` scene** — the canonical macOS location (app menu, ⌘,),
   zero layout impact on the existing windows.
3. **No local state mirror in the settings UI** — every control binds get-from-snapshot /
   set-through-bridge, so the UI always shows the persisted (possibly clamped) value.
4. **Presentation-label consolidation rides along** (all four refactors approved):
   `secondsLabel`, `focusLine`, and `menuBarTitle` computed in Kotlin (the `dayStatus`/
   `pomodoroClock` convention), plus a shared Swift `FocusClosureButtons` view replacing the
   duplicated Completed/Progressed/Resume-later row in `RingView` and `MiniView`.

## Architecture

### `:core` bridge

`TodaySnapshot` gains seven fields, following its existing conventions (numbers as `Long`,
enum-likes and display text as `String`):

```kotlin
val startMinutes: Long,      // day window start, minutes from midnight
val endMinutes: Long,        // day window end, minutes from midnight
val showSeconds: Boolean,
val themeMode: String,       // "SYSTEM" | "LIGHT" | "DARK" (ThemeMode.name)
// Presentation labels, computed once here instead of per-Swift-view (the
// dayStatus/pomodoroClock convention):
val secondsLabel: String,    // zero-padded seconds component + "s" (e.g. "07s") when
                             // showSeconds && !isFinished, else ""
val focusLine: String,       // "Focus · <intention> · <clock>" (ACTIVE),
                             // "Break · <clock>" (BREAK), "" (IDLE)
val menuBarTitle: String,    // pomodoroClock during ACTIVE/BREAK, else dayStatus
```

`focusLine` replaces the verbatim-duplicated `RingView.focusText` / `MenuBarContent.focusLine`
switches; `menuBarTitle` replaces `DayViewApp.menuBarTitle`. `MiniView` keeps its split
compact layout (intention and clock in separate slots), so it does not consume `focusLine`;
the raw `pomodoroStatus`/`focusIntention`/`pomodoroClock` fields remain for structural
decisions everywhere.

`DayViewSession` gains four passthrough setters:

```kotlin
fun setDayStart(minutes: Int) = controller.setStartMinutes(minutes)
fun setDayEnd(minutes: Int) = controller.setEndMinutes(minutes)
fun setShowSeconds(enabled: Boolean) = controller.setShowSeconds(enabled)

/** [mode] is "SYSTEM"/"LIGHT"/"DARK"; anything else degrades to SYSTEM (no FFI throw). */
fun setThemeMode(mode: String) {
    controller.setThemeMode(
        when (mode) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        },
    )
}
```

No controller change. The controller's existing clamping is the validation story:
`setStartMinutes` coerces into `0..(end − 30)` and `setEndMinutes` into
`(start + 30)..23h59` — a picker sending an invalid value gets the corrected value back on
the next snapshot emission, and the bound control snaps to it.

`TodayModel` mirrors the four setters one-line each.

### Settings scene (`SettingsView`)

`DayViewApp` adds:

```swift
Settings {
    SettingsView(model: model)
}
```

SwiftUI provides the app-menu "Settings…" item and ⌘, automatically. `SettingsView` is a
`Form` with three groups:

- **Day** — two `DatePicker(displayedComponents: .hourAndMinute)` for start and end. Each
  binds via a computed `Binding<Date>`: *get* converts `snapshot.startMinutes`/`endMinutes`
  to a `Date` anchored on an arbitrary fixed day; *set* converts the picked time back to
  minutes-from-midnight and calls `setDayStart`/`setDayEnd`. Only the hour/minute components
  are meaningful.
- **Display** — `Toggle("Show seconds")` bound to `snapshot.showSeconds` /
  `setShowSeconds`.
- **Appearance** — `Picker` with System/Light/Dark bound to `snapshot.themeMode` /
  `setThemeMode`.

No local `@State` mirrors and no save button: every control reads the snapshot and writes
through the bridge (autosave, like the JVM screen's "changes saved automatically").

### Applying the settings

- **Day window:** nothing to render — the controller recomputes the ring on the next tick.
- **Seconds:** `RingView` and `MiniView` show a small muted secondary line under the
  countdown text whenever `snapshot.secondsLabel` is non-empty — no Swift-side gating or
  `% 60`. Mirrors the JVM's muted seconds line. `dayStatus` stays "5h 09m".
- **Appearance:** a shared helper maps `themeMode` to SwiftUI:
  `"LIGHT"` → `.light`, `"DARK"` → `.dark`, else `nil`; applied via
  `.preferredColorScheme(...)` on the three content roots — `MainWindowRoot`, the mini
  scene's content, and `SettingsView`. (The menu-bar dropdown follows the system menu
  appearance; out of scope.)

### Presentation-label consolidation (Swift side)

- `MenuBarExtra(model.snapshot.menuBarTitle)` — the `DayViewApp.menuBarTitle` computed
  property is deleted.
- `RingView` renders `snapshot.focusLine.isEmpty ? "Idle" : snapshot.focusLine` (the "Idle"
  placeholder is main-window-specific presentation); `MenuBarContent` renders the line only
  when non-empty. Both local switches are deleted.
- New shared `FocusClosureButtons(model:)` Swift view holding the Completed / Progressed /
  Resume later row (`.bordered`, green/orange/plain, the exact Phase 5a outcome strings);
  `RingView.closureSection` and `MiniView.focusCard` both use it.

## Data flow

```
SettingsView control edited
  -> TodayModel setter -> DayViewSession setter -> controller setter (clamps) -> persist
  -> stateFlow emits -> snapshot updates
  -> bound control re-reads (shows clamped value); ring/mini/menu re-render;
     preferredColorScheme recomputed
```

## Testing / done criteria

- **`:core` unit tests** (`DayViewSessionTest`, `:core:jvmTest`, existing pattern):
  - `setDayStart(600)` → snapshot `startMinutes == 600`; `setDayEnd(1200)` → `endMinutes
    == 1200`.
  - Clamping visible through the bridge: with end at 1080, `setDayStart(1439)` → snapshot
    `startMinutes == 1050` (end − 30).
  - `setShowSeconds(false)` → snapshot `showSeconds == false` and `secondsLabel == ""`.
  - `setThemeMode("DARK")` → snapshot `themeMode == "DARK"`; `setThemeMode("garbage")` →
    `"SYSTEM"`.
  - Labels: with seconds on and the day running, `secondsLabel` matches `^\d{2}s$`; after
    `startFocus`, `focusLine` starts with "Focus · " and `menuBarTitle == pomodoroClock`;
    when idle, `focusLine == ""` and `menuBarTitle == dayStatus`.
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`, app launches;
  Settings opens via ⌘,. Manual smoke test (GUI clicks blocked in-sandbox): editing the
  start/end pickers moves the ring immediately; entering an invalid window snaps the picker
  back to the clamped value; the seconds line appears/disappears with the toggle in both
  windows; Appearance forces light/dark and System follows the OS.

## Risks

- **Date↔minutes conversion** — the `DatePicker` binding must use one fixed anchor day and
  read back only hour/minute; DST-shifting anchors would corrupt the minutes. Use a fixed
  epoch-based anchor via `Calendar.current` components, not "today".
- **Binding feedback loop** — a bound control that writes on every snapshot *get* would
  loop; the bindings write only in *set* (user edits), so ticks just re-read.
- **Seconds line jitter** — the mini's compact layout must reserve space or tolerate the
  line appearing/disappearing; acceptable at this size, checked in the smoke test.

## Roadmap after this phase (context only)

Native EventKit + net-time arcs (its settings controls join this scene), sounds, presence
tracking/on-goal apps, sync, packaging/CI cutover, macOS Widget.
