# Mobile detour tap pop-up — design

Date: 2026-07-16
Status: approved

## Problem

On touch devices, tapping the countdown ring only hit-tests focus-session bands and
calendar-busy arcs (`DayViewTodayScreen.kt`, touch tap branch of `CountdownCircle`).
Detour arcs — the lane just outside the ring — are ignored by taps, so the only way to
see a detour's details on mobile is the long-press scrub. Desktop already shows a
tooltip on mouse hover.

## Decision

A tap on a detour arc shows the same anchored pop-up the desktop mouse hover shows:
`HoverTooltip` + `DetourReadoutDetails` (category, optional description, duration),
anchored at the tap position. A second tap on the same detour, or a tap elsewhere on
the ring, closes it; a tap on another detour switches to it.

Alternatives considered and rejected:

- Opening the detour list panel (the desktop mouse-click behaviour): does not isolate
  the tapped detour.
- Pop-up plus an "open list" action: richer but more complex; not needed now.

## Behaviour

In the touch tap branch of `CountdownCircle`'s gesture handler:

1. Hit-test the detour lane first with the existing radius-aware `hitTestDetourBody`
   (same test as the mouse: outer radial band, angular tolerance included).
2. Tap priority becomes **detour → session band → busy arc**, matching the mouse hover
   priority.
3. On a detour hit, compute the next pop-up state with a new pure helper
   `nextHoveredDetourOnTap(current, tapped, position)`, modelled on
   `nextHoveredBusyOnTap` / `nextHoveredSessionOnTap` (open / switch / close rules).
4. Exclusivity: one pop-up at a time. A detour hit clears the busy and session pop-ups;
   a session or busy hit (or an empty tap) clears the detour pop-up.

Rendering requires no new UI: the `hoveredDetour?.let { HoverTooltip { … } }` block
already exists and uses `categoryColor = colors.cloud`, which the tap path keeps
(consistent with the desktop tooltip; the scrub readout's per-category colour is
untouched).

`HoveredDetourBody` moves from `private` to `internal` so the pure helper and its
tests can use it, matching `HoveredBusyArc` and `HoveredFocusSession`.

## Testing

Unit tests for `nextHoveredDetourOnTap` (open on tap, switch on another detour, close
on same detour, close on empty tap) alongside the existing tap-rule tests in
`shared/src/desktopTest/kotlin/fr/dayview/app/BlockedZoneTapTest.kt`. No UI gesture
test: the project tests these tap rules as pure functions.

## Out of scope

- Mouse hover / click behaviour (unchanged).
- Long-press scrub readout (unchanged).
- The detour list panel and detour editing.
