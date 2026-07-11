# Start / stop focus from the mini window

## Problem

On desktop (macOS), the mini window and the menu-bar tray both *display* focus
state but neither can *start* it. To begin a focus (pomodoro) session the user
must open the full DayView window and use the focus bottom sheet. The mini
window is meant to be the always-on-top, low-friction companion; it should be
able to start and stop focus on its own.

Android already solves the equivalent problem with a Quick Settings tile
(`DayViewFocusTileService` / `focusTileAction`): it starts focus directly when
an intention exists and otherwise opens the app. This spec brings a comparable
capability to the desktop mini window.

## Scope

- **In scope:** desktop mini window (`DayViewMiniApp`) gains the ability to
  start focus (via an intention modal) and stop an active focus session.
- **Out of scope:** the menu-bar tray stays display-only. No pomodoro-duration
  control in the mini window (it reuses the saved duration). Android is
  unchanged.

## Behavior

### Idle (`PomodoroStatus.IDLE`)

- The mini window shows a compact **Start focus** button, styled to match the
  existing amber focus panel.
- Pressing it opens an **intention modal**: an in-window overlay (a scrim `Box`
  over the mini-window content plus a centered card). The card contains:
  - a single-line text field pre-filled with the saved `focusIntention`,
  - a **Start** action, disabled while the field is blank (trimmed),
  - a way to dismiss without starting (a Cancel action and/or tapping the
    scrim).
- Confirming **Start**:
  1. persists the trimmed intention (`≤ 100` chars),
  2. starts a pomodoro of the saved duration,
  3. dismisses the modal.

The modal opens **every** time (pre-filled), so the user can review or edit the
intention before each session. The blank-disabled Start button is what enforces
the existing "focus needs an intention" rule on this surface.

### Active / Break (`PomodoroStatus.ACTIVE` or `BREAK`)

- The existing `MiniFocus` panel gains a **Stop** control that ends the current
  focus session and returns the window to the idle state.

## Components

- **`DayViewMiniApp`** becomes interactive:
  - New callbacks: `onStartFocus(intention: String)` and `onStopFocus()`.
  - Owns local UI state: whether the modal is shown, and the draft intention
    text (seeded from the saved intention via `remember(focusIntention)`).
  - Existing value parameters (progress, goal, pomodoro, intention, …) are
    unchanged.
- **`MiniFocusStart`** (new): the idle call-to-action button.
- **`FocusIntentionModal`** (new): the overlay — a scrim over the content and a
  centered card holding the text field and Start / Cancel actions. It stays
  inside the mini window; it does not open a separate OS window.
- **`MiniFocus`** (existing): gains the Stop control.

## Data flow / wiring (`Main.kt`)

The desktop `Main.kt` already holds `preferences` and observes changes through
`preferences.observe { preferenceSnapshot = it }`, which feeds every surface
(mini window, tray, status item). The new callbacks reuse that channel:

- `onStartFocus(intention)`:
  1. `preferences.saveFocusIntention(intention.trim().take(100))`
  2. `preferences.savePomodoro(pomodoroMinutes, endMillis)` where
     `endMillis = nowMillis + pomodoroMinutes * 60_000L`.
  This mirrors `DayViewController.startPomodoro()` and the Android tile's start
  path.
- `onStopFocus()`: `preferences.savePomodoro(pomodoroMinutes, null)`.

No extra plumbing is needed: the observe loop propagates the persisted changes
back into `preferenceSnapshot`, which re-renders the mini window and updates the
tray / status item.

## Start computation helper

Factor the pure start math into a small helper so it is unit-testable without a
UI, following the precedent of `focusTileAction` (Android) and the
detector/status-item helpers on desktop:

```
fun focusStartEndMillis(nowMillis: Long, durationMinutes: Int): Long =
    nowMillis + durationMinutes.coerceIn(5, 180) * 60_000L
```

`Main.kt`'s `onStartFocus` uses this helper; the composables stay thin.

## Testing

- A `desktopTest` covers `focusStartEndMillis` (coercion at both bounds and a
  representative in-range duration), matching the style of the existing
  `FocusDriftDetectorTest` / `MacFocusStatusItemTest`.
- The intention-trimming/`take(100)` behavior already matches
  `DayViewController.setFocusIntention`; no new logic there beyond reuse.

## Non-goals / rationale

- **Menu bar left as display-only:** a tray is a flat list of menu items with no
  text-entry affordance, so an intention modal cannot live there. Starting focus
  from the tray was explicitly deferred.
- **No duration control in the mini window:** keeps the small window simple; the
  saved duration is a deliberate, infrequently-changed preference edited in the
  full app.
