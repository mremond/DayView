# Touch long-press ring scrub

## Problem

On desktop, hovering the mouse over a busy arc (calendar event) or a detour body on the
countdown ring pops a tooltip with its label and time range. Touch devices have no hover,
so those labels are unreachable — and the ring's small targets (thin arcs, tiny detour
bodies) would be awkward to tap precisely even if we wired taps.

## Goal

A touch-native, imprecise gesture — like an iPod click wheel — that lets a finger sweep a
mark around the ring and read out *everything on the ring* at the mark's angular position,
without needing to hit a small target.

## Interaction model

- **Touch only.** Mouse behaviour is unchanged: the existing hover tooltips stay exactly
  as they are.
- **Arm:** press and hold (~400 ms) anywhere inside the circle. On arm, a short haptic
  tick fires and a mark appears on the ring.
- **Scrub:** while dragging, the mark tracks the **angle** from the ring's center to the
  finger and snaps onto the ring's arc radius. The finger's distance from center is
  ignored — this is the iPod-wheel feel, so no precise targeting is required.
- **Dismiss:** lifting the finger clears the mark and the readout. A cancel (e.g. gesture
  interrupted) does the same.

## What the mark reads — pure `RingReadout`

A new pure (non-Compose) function computes what sits under a given angle:

```
ringReadoutAt(
    angleDegrees: Float,
    windowStart: Instant,
    windowEnd: Instant,
    busyBlockArcs: List<BusyBlockArc>,
    detourBodies: List<DetourBody>,
    focusArcs: List<FocusArc>,
    momentAngleDegrees: Float?,   // null before the day starts / after it ends
): RingReadout
```

`RingReadout` is a small data model listing every layer present at that angle (overlaps
allowed), with the time of day as the header:

- **Time of day** — always, from `angleToInstant(angleDegrees, windowStart, windowEnd)`.
- **Now** — a flag set when the angle is within a small tolerance (~3°) of
  `momentAngleDegrees`.
- **Busy** — calendar name + non-blank titles + start/end range, when a busy arc contains
  or is within the existing angular tolerance of the angle (reuse `angularDistanceToArc`,
  same ~5° margin the hover hit-test uses so thin arcs stay reachable).
- **Detour** — motif + start/end range + duration, when a detour body's angular span
  contains the angle. The body carries `start`/`end` instants; its angular span is derived
  from those over the window (not just the midpoint `angleDegrees`), so short and long
  detours are both reachable. A minimum angular width keeps very short detours hittable.
- **Focus** — a flag, when a focus arc contains the angle.

Because it is pure, it is unit-tested directly on the returned model (no `stringResource`
assertions — those don't resolve under the test harness; assert on the model's fields).

## Readout panel

A fixed overlay strip inside the circle `Box`, shown only while armed, defaulting to
**bottom-center** (a single constant makes top/bottom a one-line change). It renders the
`RingReadout`:

- Large time-of-day.
- A "now" pill when the now flag is set.
- Any busy / detour / focus lines, each in its existing layer color (busy palette, detour
  palette).

Its location is fixed and never sits under the thumb, unlike the mouse tooltip which
follows the pointer.

## The mark

A distinct dot + soft halo drawn on the ring arc at the scrub angle, visually different
from the amber current-moment marker, appearing on arm and following the drag. Drawn at
the same arc radius the ring uses, at the scrub angle.

## Gesture wiring

In `CountdownCircle`, add a touch-filtered `pointerInput`:

- `awaitEachGesture` → `awaitFirstDown(requireUnconsumed = false)`; proceed only if
  `down.type == PointerType.Touch`.
- `awaitLongPressOrCancellation(down.id)` to detect the hold; on success, arm — set scrub
  state, fire haptic via `LocalHapticFeedback.performHapticFeedback(LongPress)`.
- Drag loop: consume pointer moves, update `scrubAngle` from the pointer position relative
  to center; end on up / cancel, clearing scrub state.

This composes alongside the existing mouse-hover `pointerInput` (which only acts on
`PointerType.Mouse`), so the two do not interfere. Scrub is available whenever the ring is
drawn, since the time-of-day readout is meaningful even with no calendar/detour data.

## Files

- **New** `composeApp/src/commonMain/kotlin/fr/dayview/app/RingScrub.kt` — `RingReadout`
  data model + `ringReadoutAt` pure function (and a small helper for a detour body's
  angular span).
- **New** `composeApp/src/desktopTest/kotlin/fr/dayview/app/RingScrubTest.kt` — unit tests
  for `ringReadoutAt` across empty ring, busy, detour, focus, overlap, and "now" cases.
- **Edit** `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` —
  `CountdownCircle`: add the touch scrub `pointerInput`, the scrub mark drawing, and the
  fixed readout overlay composable.

## Out of scope

- No change to mouse hover tooltips.
- No tap-to-open actions from the scrub (release simply dismisses).
- No persistence — scrub state is transient UI state.
