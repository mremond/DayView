# Tap a blocked calendar zone to show its label (mobile)

## Problem

On desktop, hovering a blocked (busy) arc on the ring shows a `HoverTooltip` with the
calendar name, event titles and time range. On touch there is no equivalent discrete
gesture: the only way to reveal a blocked zone's label is the long-press-and-scrub
(`RingScrubReadout`), which requires holding and dragging around the ring. A simple tap on
a blocked zone does nothing today.

Goal: on touch, a single tap on a blocked zone shows the same busy-label tooltip that mouse
hover already shows, anchored at the touched point.

## Scope

- Blocked (busy) zones only — not detour bodies, not focus arcs.
- Touch input only. Desktop mouse hover behaviour is unchanged.
- No data-model changes: `BusyBlockArc` already carries `titles`, `calendarName`, `start`,
  `end`.

## Design

### Reuse

Everything the tooltip needs already exists in
`composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`:

- `HoveredBusyArc(arc, position)` state (`hoveredBusy`) — already drives the tooltip render.
- `HoverTooltip` composable — pointer-anchored panel showing `calendarName` + `titles` +
  time range.
- `hitTestBusyArc(position, width, height, busyBlockArcs)` — maps a pointer offset to the
  arc under it (5° tolerance), or `null`.

The tap gesture only needs to set `hoveredBusy`, which the render at
`hoveredBusy?.let { ... }` already consumes.

### Gesture

The current touch handler `scrubModifier` uses `awaitLongPressOrCancellation(down.id)`,
which returns `null` on a tap and discards the up position — so a tap cannot be recovered
from it. Merge tap and long-press detection into the single `awaitEachGesture`:

```
awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (down.type != PointerType.Touch) return@awaitEachGesture
    var longPress = false
    val up = try {
        withTimeout(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation()
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        longPress = true   // timed out -> long press
        null
    }
    when {
        longPress -> {
            // existing scrub loop, unchanged
        }
        up != null -> {
            // tap -> resolve arc and update hoveredBusy
            val tapped = hitTestBusyArc(up.position, size.width, size.height, busyBlockArcs)
            hoveredBusy = nextHoveredBusyOnTap(hoveredBusy, tapped, up.position)
            up.consume()
        }
        // else: waitForUpOrCancellation returned null (cancelled) -> nothing
    }
}
```

The timeout path and the plain-cancel path both leave `up == null`; the `longPress` flag
set in the `catch` distinguishes them, so a genuine long-press timeout runs the scrub while
a cancel does nothing. This is the standard Compose pattern for "tap vs long press"; the
existing scrub loop moves verbatim into the long-press branch.

### Close behaviour ("tap to close")

The tap-to-state decision is a pure function, unit-testable without Compose:

```
fun nextHoveredBusyOnTap(
    current: HoveredBusyArc?,
    tapped: BusyBlockArc?,
    position: Offset,
): HoveredBusyArc? = when {
    tapped == null -> null                       // tapped empty ring -> close
    current?.arc == tapped -> null               // tapped the shown zone -> close
    else -> HoveredBusyArc(tapped, position)     // tapped another zone -> switch
}
```

Rules:
- Tap another zone → switch the tooltip to it.
- Tap the currently shown zone → close.
- Tap an empty part of the ring → close.

Limitation: a tap entirely outside the ring does not close the tooltip, because the gesture
handler only covers the ring-sized box. This mirrors desktop hover (the tooltip clears on
hover-exit, not on an outside click) and is acceptable. No auto-dismiss timeout.

### Interaction with mouse hover

On a pure touch device there is no mouse, so the touch tap and the hover `Move`/`Exit`
handlers never both fire. On a hybrid touchscreen+mouse device the mouse `Move` handler
would re-drive `hoveredBusy` on the next mouse movement; this edge case is out of scope and
left as-is.

## Testing

- Unit test `nextHoveredBusyOnTap` (pure): switch to another arc, close on same arc, close
  on empty tap.
- The gesture wiring itself is a thin glue layer over already-tested primitives
  (`hitTestBusyArc`, `HoverTooltip`) and is not covered by a UI test, consistent with the
  repo's constraints on simulating touch long-press/tap in `desktopTest`.

## Files

- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` — merge tap
  detection into `scrubModifier`; add `nextHoveredBusyOnTap`.
- `composeApp/src/desktopTest/...` (or the matching common test source) — unit test for
  `nextHoveredBusyOnTap`.
