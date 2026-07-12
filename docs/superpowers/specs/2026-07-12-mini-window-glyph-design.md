# Mini ⇄ Main window glyphs

## Goal

Replace the text "MINI" button in the main window header with a picture-in-picture
glyph, and give the mini window an always-visible "expand" glyph that returns to the
main window. Today the mini window can only be dismissed via the tray menu; this adds an
in-window control.

## Scope

Desktop only. No behavior change to window switching itself — only the affordances that
trigger it.

## Components

### 1. Shared glyph composables — `WindowGlyphs.kt` (new, `commonMain`)

The app hand-draws its small glyphs (the focus-start `+`, the stop square) rather than
depending on Material Icons. Follow that idiom. Two `@Composable` functions, each drawing
paths with `Canvas`, taking a `color: Color` and a `Modifier`:

- `MiniWindowGlyph` — outer rounded rectangle outline with a small filled rectangle in the
  bottom-right corner (picture-in-picture). Represents "shrink to mini window".
- `ExpandWindowGlyph` — two diagonal corner arrows pushing outward (top-right and
  bottom-left). Represents "grow back to full window".

Placing both in one shared file keeps `DayViewTodayScreen.kt` and `DayViewMiniApp.kt` from
each carrying a private copy, and gives the two glyphs a single home.

### 2. Main window header — `DayViewTodayScreen.kt` (`Header`, ~line 582)

Replace the `Text(mini_window_button)` with a clickable `Box` wrapping `MiniWindowGlyph`.
Preserve everything the text button had:

- `onOpenMiniWindow` click wiring (unchanged),
- `Role.Button` and `minimumInteractiveComponentSize()`,
- `colors.muted` glyph color, consistent with the SETTINGS label beside it,
- the `mini_window_button` string, now passed as the `onClickLabel` (accessibility) instead
  of visible text.

Add a `testTag` so the button stays assertable after the visible text is gone.

### 3. Mini window — `DayViewMiniApp.kt`

Add a parameter `onOpenMainWindow: () -> Unit` to `DayViewMiniApp`.

Render `ExpandWindowGlyph` inside a clickable `Box` pinned to the top-right corner. Use the
existing outer `Box(Modifier.fillMaxSize())` with `Alignment.TopEnd` so the glyph overlays
the corner without disturbing the centered countdown/goal/focus column beneath it.

- `Role.Button`, `minimumInteractiveComponentSize()`,
- `colors.muted` color,
- `onClickLabel = stringResource(desktop_open_full_window)` — reuse the existing string that
  the tray "Open full window" item already uses,
- a `testTag` for assertions.

The glyph is always visible (not hover-gated): simpler and more discoverable.

### 4. Wiring — `Main.kt` (mini window `DayViewMiniApp(...)` call, ~line 312)

Pass:

```kotlin
onOpenMainWindow = {
    isMiniWindowVisible = false
    isWindowVisible = true
}
```

This mirrors the existing tray "Open full window" action, which flips the same two flags.

## Testing

`desktopTest` convention in this repo: assert via `testTag`, never via `stringResource`
text (unresolved under `runComposeUiTest` on CI). Add tags to both glyph buttons and assert:

- the mini-window button exists in the main Today screen and, on click, invokes the
  `onOpenMiniWindow` callback,
- the expand button exists in `DayViewMiniApp` and, on click, invokes `onOpenMainWindow`.

Test the pure screen composables directly (not `DayViewApp`), per the harness convention.

## Out of scope

- Hover-reveal / fade animations on the return glyph.
- Any change to the tray menu items or window sizing/behavior.
- New user-facing strings (existing `mini_window_button` and `desktop_open_full_window`
  are reused as accessibility labels).
