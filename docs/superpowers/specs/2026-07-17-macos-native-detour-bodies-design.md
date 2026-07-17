# macOS Native — Detour ring bodies + hover (Path B, Phase 9b)

## Context

Phase 9a brought detours to the native main window — capture, tally, daily total, edit list
— but the episodes are not yet drawn on the ring. On the Compose/JVM app each detour rides a
lane just **outside** the ring (the mirror of the calendar-busy lane inside it): a small
colored body across its real duration, one color per source, with hover showing the motif and
times. The controller already produces `detourBodiesState` (angles, colorIndex, category,
description, exact start/end — floored to a visible minimum, midpoint-outside episodes
dropped, colored from the same `detourSources` mapping the 9a tally uses).

Phase 9b is the pure-rendering-plus-hover half, the exact analogue of 7b (busy arcs): a
snapshot arc list, an outer lane on `DayRingCanvas`, and hover. Main window only (the JVM
mini draws no detour bodies, matching 9a and the busy-arc parity).

## Goals

- Snapshot exposes a primitives-only detour-body list with a Kotlin-computed hover label.
- `DayRingCanvas` draws the detour bodies on an outer lane (glow+core, mirror of the busy
  lane) in the main window.
- Hovering a body shows its motif and times.
- A shared, tested angular hit helper serving both busy (5°) and detour (6°) tolerances.

## Non-Goals

- Detour bodies in the mini window (parity: the JVM mini has none).
- Tap/click interaction; ring scrubbing (dropped for macOS).
- The off-window detour tag (`detoursOffWindowTotalToday`) — deferred.
- Focus/engaged arcs and the focus-session band (their own phase).
- Any `:core` controller change — `detourBodiesState` consumed as-is.

## Decisions (from brainstorming)

1. **Shared hit helper** — extract the wrap-aware nearest-arc logic in `busyArcIndexAt` into
   a generic `angularArcIndexAt(starts, sweeps, angle, toleranceDegrees)`; `busyArcIndexAt`
   delegates at 5°, `detourBodyIndexAt` at 6° (the JVM's detour tolerance). Busy's tests stay
   green; the generic gets its own.
2. **Main window only**; hover label from the exact Instants (the 7b/9a convention).

## Architecture

### Bridge (`TodaySnapshot`)

```kotlin
/** One detour episode projected on the ring's outer lane, ready for drawing + hover. */
data class DetourBodySnapshot(
    val startAngleDegrees: Double, // -90° anchor, clockwise (same as busy arcs)
    val sweepDegrees: Double,      // already floored to a visible minimum by :core
    val colorIndex: Long,          // matches the 9a tally source color
    val hoverLabel: String,        // "<motif> · <start> – <end>"
)

val detourBodies: List<DetourBodySnapshot>,   // new field
```

Mapped in `toTodaySnapshot` from `detourBodiesState`. `hoverLabel` =
`"${category} · ${formatWallClock(startH, startM, use24Hour)} – ${formatWallClock(endH,
endM, use24Hour)}"` from the body's exact `start`/`end` Instants (`category` is always
non-blank — `addDetour`/`updateDetour` reject blank). Empty unless there are detours today
(the controller day-gates `detourBodiesState`).

### Shared hit helper (`:core`, refactor)

Extract from the existing `busyArcIndexAt`:

```kotlin
/**
 * Index of the arc whose [starts]/[sweeps] contain [angleDegrees], or the nearest within
 * [toleranceDegrees] of an edge; -1 otherwise. Wrap-aware (-90° anchor, clockwise). The
 * two lists are parallel and equal length.
 */
fun angularArcIndexAt(
    starts: List<Double>,
    sweeps: List<Double>,
    angleDegrees: Double,
    toleranceDegrees: Double,
): Int
```

`busyArcIndexAt(arcs, angle)` becomes `angularArcIndexAt(arcs.map{it.startAngleDegrees},
arcs.map{it.sweepDegrees}, angle, 5.0)`; new `detourBodyIndexAt(bodies, angle)` delegates at
`6.0`. `normalizeRingDegrees` moves into the generic. Busy's existing `BusyArcIndexTest`
stays unchanged and green (it exercises the delegate); the generic gets direct tests.

### Rendering — `DayRingCanvas` outer lane

`DayRingCanvas` gains `detourBodies: [DetourBodySnapshot] = []` and a static
`detourRadiusFactor: CGFloat = 0.95`. After the busy lane, draw each body on an **outer**
lane at `radius + lineWidth × detourRadiusFactor` (the mirror of the inner busy lane, which
is at `radius − lineWidth × busyRadiusFactor`), glow+core exactly like busy: glow
`detourColor(i)` @ 0.16, stroke `lineWidth × 0.7`; core @ 0.92, stroke `lineWidth × 0.42`;
round caps. The outer lane sits between the main ring and the hour ticks — the existing inset
40 leaves room (the JVM budgets the same `strokeWidth × 0.95` outset). `RingView` passes
`snapshot.detourBodies`; `MiniView` passes nothing.

### Hover — two bands, one caption (`RingView`)

The current `busyArcHit` generalizes: the pointer's radial distance selects the lane —
- outer band around `radius + lineWidth × detourRadiusFactor` (± `0.7×lineWidth/2 + 6`) →
  `detourBodyIndexAt` over `snapshot.detourBodies`;
- inner band around `radius − lineWidth × busyRadiusFactor` → `busyArcIndexAt` (unchanged
  busy behavior).

The hit's `hoverLabel` floats in the existing caption. `HoveredBusyArc`/`hoveredBusy`
generalize to a shared "hovered arc" (label + position) covering both lanes; the busy path is
preserved. No tap. The band geometry constants come from `DayRingCanvas` statics so drawing
and hit-testing can't drift (the 7b invariant).

## Data flow

```
controller.detourBodiesState (existing; day-gated, floored, colored)
  -> toTodaySnapshot(use24Hour) -> snapshot.detourBodies (angles + colorIndex + hoverLabel)
  -> DayRingCanvas outer lane (RingView only)
pointer -> polar (Swift) -> outer band? detourBodyIndexAt : inner band? busyArcIndexAt (Kotlin)
  -> hover caption
```

## Testing / done criteria

- **`:core:jvmTest`:**
  - Snapshot mapping: with a detour today, `detourBodies` carries the expected
    `colorIndex` (matching the source order) and a `hoverLabel` equal to the string built
    in-test from the body's own local times via `formatWallClock` (host-TZ-portable exact
    assertion); 12-hour session → the 12-hour form; no detours → empty.
  - `angularArcIndexAt`/`detourBodyIndexAt`: containment, gap miss, anchor wrap, the 6°
    tolerance boundary (a hit at 6° past an edge, a miss at 7°), empty → −1.
  - `busyArcIndexAt` still passes its existing `BusyArcIndexTest` unchanged (the delegate
    preserves the 5° behavior).
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test:
  declare a detour → a colored body appears on the outer lane, its color matching the tally
  chip for that source; a second source → a second color; hovering a body shows
  "motif · start – end" to the minute honoring the system clock; busy-arc hover still works
  on the inner lane; the mini window's ring has no detour bodies.

## Risks

- **Two hover bands overlapping** — the outer (detour) and inner (busy) bands are at
  different radii and must not both claim a pointer. The radial band checks are mutually
  exclusive by radius; if a pointer somehow falls in both tolerances, detour (outer) is
  checked first. Verified in the smoke test.
- **Outer lane clipping the ticks** — at `radius + lineWidth × 0.95` with a `0.7×lineWidth`
  glow, the lane's outer edge stays inside the hour ticks given inset 40; confirmed
  arithmetically (main radius = min/2 − 40; lane outer edge ≈ min/2 − 16; ticks start at
  min/2 − 11). Checked visually in the smoke test.
- **Refactor regressing busy** — `busyArcIndexAt` becomes a thin delegate; its existing
  tests are the guard and must stay unmodified.

## Roadmap after this phase (context only)

Detours are complete after 9b. Next per the checklist: presence/on-goal (frontmost-app
watcher, drift nudges, engaged arcs, dock badge/bounce, `sessionOffGoal`), then must-dos,
sounds, history, day-over, sync, i18n, and the packaging/CI cutover.
