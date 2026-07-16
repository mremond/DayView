# macOS Native — Busy arcs + hover (Path B, Phase 7b)

## Context

Since Phase 7a the native macOS app computes net time from calendar-busy intervals, but the
ring does not show *where* the busy blocks sit. On the Compose/JVM app, each merged busy
interval is drawn as a thin colored arc on a concentric lane inside the main ring (one
stable color per calendar), and hovering an arc shows the event title(s) and exact times.
The controller already produces everything (`busyBlockArcsState`: angles, color index,
titles, calendar name, and the exact `start`/`end` Instants); this phase is pure rendering
plus a hover interaction.

**Parity findings that scope this phase:**
- The JVM **mini window does not render busy arcs** (its ring call passes no arc data) —
  so 7b targets the **main window only**.
- Ring **scrubbing is DROPPED** for macOS (parity decision, 2026-07-16) — the hover here is
  the net-time arc tooltip only. Touch-tap pop-ups are the Android path; no tap interaction
  on macOS.

## Goals

- Snapshot exposes a primitives-only busy-arc list with a Kotlin-computed hover label.
- `DayRingCanvas` draws the busy lane with the JVM geometry and per-calendar colors.
- Hovering an arc in the main window shows a small floating label (title(s) + times).
- Start the 12/24-hour parity item: the hover times honor the system clock format.

## Non-Goals

- Busy arcs in the mini window (JVM parity: it has none).
- Tap/click interaction on arcs; ring scrubbing (dropped).
- Detour bodies and focus/engaged arcs (their own phases).
- Full DayView theming — only the busy palette constants arrive now; the visual-identity
  pass owns the rest.
- Any `:core` controller change — `busyBlockArcsState` is consumed as-is.

## Decisions (from brainstorming)

1. **Main window only** (JVM parity).
2. **Hover label computed in Kotlin** (the Phase 6 label convention), from the arc's exact
   `start`/`end` Instants — never from an angle round-trip (the `:core` comment on
   `BusyBlockArc` warns the float round-trip shaves minutes).
3. **Hit-testing split by testability:** angle containment (wrap-aware) is a pure `:core`
   function under `jvmTest`; only the geometry half (pointer → polar, radius-band check)
   stays in Swift.

## Architecture

### Bridge (`TodaySnapshot`)

```kotlin
/** One calendar-busy block projected on the ring, ready for native drawing + hover. */
data class BusyArcSnapshot(
    val startAngleDegrees: Double, // -90° anchor, same convention as momentAngleDegrees
    val sweepDegrees: Double,
    val colorIndex: Long,          // stable per calendar; Swift maps % palette size
    val hoverLabel: String,        // "Standup · 09:00–09:30" — computed here, see below
)

val busyArcs: List<BusyArcSnapshot>,   // new TodaySnapshot field
```

Mapped in `toTodaySnapshot` from `busyBlockArcsState` (already empty when net time is off
or the stored busy layer is stale — no extra gating). The label:

- Name part: `titles` joined with ", "; blank → `calendarName`; blank → `"Busy"`.
- Times part: `formatWallClock(...)` (existing `:core` function) applied to the arc's
  `start`/`end` converted to local hour/minute, joined with "–".
- Joined: `"<name> · <start>–<end>"`.

**12/24-hour plumbing:** `toTodaySnapshot` gains a `use24Hour: Boolean = true` parameter
(threaded from `DayViewSession`, which takes it as a constructor value). `DayViewNative`
detects the system preference in `macosMain` via the `NSDateFormatter` template probe
(`dateFormatFromTemplate("j")` containing `"a"` → 12-hour). Detected once at session
creation — clock-format changes mid-run are picked up on next launch (acceptable; the JVM
reads it once per composition too).

### Hit-testing helper (`:core` commonMain)

```kotlin
/**
 * Index of the arc containing [angleDegrees] (same -90°-anchored, clockwise convention as
 * the arcs), or -1. Wrap-aware: an arc crossing the top (e.g. start 350°-equivalent,
 * sweep 30°) matches angles on both sides of the anchor.
 */
fun busyArcIndexAt(arcs: List<BusyArcSnapshot>, angleDegrees: Double): Int
```

Pure and unit-tested (`jvmTest`): containment, gap misses, wrap across the −90° anchor,
empty list. (The JVM's `hitTestBusyArc` lives in `:shared` with Compose `Offset` types —
this is the shareable, geometry-free half of it.)

### Rendering (`DayRingCanvas`)

Gains `busyArcs: [BusyArcSnapshot] = []`. Geometry mirrors the JVM main ring
(`DayViewTodayScreen.kt`): the busy lane is concentric inside the main lane at
`busyInset = inset + lineWidth × 0.95`, stroke `lineWidth × 0.7`, round caps, drawn after
the track and before the remaining sweep. Color: `busyPalette[colorIndex % count]`, with the
palette cribbed verbatim from `DayViewTheme.colors.busy` — dark list `0xFF6EC6FF` sky,
`0xFF6FD8C9` teal, `0xFF8AA6FF` periwinkle, `0xFFB39DFF` violet, `0xFF7FB4CC` slate cyan,
`0xFF9FC0E8` steel; light list `0xFF2C6FA6`, `0xFF2E8B84`, `0xFF3F52A8`, `0xFF6A4FA8`,
`0xFF34738A`, `0xFF4E6E96` — selected by the current color scheme. These constants live in
a small `BusyPalette.swift`; the visual-identity pass will fold them into the full theme
later.

`RingView` passes `model.snapshot.busyArcs`; `MiniView` passes nothing (default).

### Hover (`RingView`, main window only)

The ring area gets `.onContinuousHover`:

- Pointer → polar: distance and angle from the view center (the same center/radius math as
  `DayRingCanvas`; the geometry constants are shared so the hit band matches the drawn
  lane).
- Radius must fall in the busy-lane band ± a small tolerance (~6 pt) — Swift-side check.
- Angle (normalized to the −90° clockwise convention) → `busyArcIndexAt` — Kotlin-side.
- Hit → a small floating panel (caption text on a rounded background) near the cursor with
  the arc's `hoverLabel`; miss/`.ended` → panel hidden.

No state beyond the transient hovered index/position (`@State` local to `RingView`).

## Data flow

```
controller.busyBlockArcsState (existing; net-time gated, day-tagged)
  -> toTodaySnapshot(use24Hour) -> snapshot.busyArcs (angles + colorIndex + hoverLabel)
  -> DayRingCanvas busy lane (RingView only)
pointer moves -> polar (Swift) -> radius band ok? -> busyArcIndexAt (Kotlin) -> hover panel
```

## Testing / done criteria

- **`:core:jvmTest`:**
  - Snapshot mapping: with net time enabled and a busy interval, `busyArcs` carries the
    expected angles/`colorIndex`, and the label equals the string built in the test from
    the fixture's own local hour/minute via `formatWallClock` (host-TZ-portable exact
    assertion — pins the "name · start–end" joining and the title→calendar-name→"Busy"
    fallback, while `formatWallClock`'s formatting is covered by its own existing tests);
    12-hour session → the label carries the 12-hour form for the same fixture; net time
    disabled → empty list.
  - `busyArcIndexAt`: inside/outside containment, boundary angles, wrap across the anchor,
    empty list → −1.
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test:
  with net time on and busy events today, colored arcs appear on the inner lane of the main
  ring where the events sit; hovering one shows "title · start–end" near the cursor and it
  disappears on leave; two calendars get two stable colors; the mini window stays arc-free;
  clock format follows the system 12/24-hour setting.

## Risks

- **Geometry drift between drawing and hit-testing** — mitigated by sharing the same
  inset/lineWidth constants between `DayRingCanvas` and the hover math (one Swift source of
  truth for the lane geometry).
- **`onContinuousHover` coordinate space** — must be the same space as the canvas frame
  (use `.local`); verified in the smoke test.
- **Label time zone** — `start`/`end` converted with the system time zone via the existing
  kotlinx-datetime path; the mapping test pins hour/minute values with a fixed zone-safe
  fixture (full-day window, midday instant — the established test pattern).

## Roadmap after this phase (context only)

Per the parity checklist: visual identity pass (palette/styled ring — will absorb
`BusyPalette.swift`), then detours, presence/on-goal, and onward to cutover.
