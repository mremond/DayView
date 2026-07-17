# macOS Native — Visual identity pass (Path B, Phase 8)

## Context

The native SwiftUI app (Path B; phases 1–7b merged) is functionally ahead of schedule but
visually plain vanilla SwiftUI: default backgrounds, `GroupBox` cards, a bare two-stroke
ring, and the countdown text sitting below the dial. The Compose/JVM app has a strong
visual identity — the ink/glow palette, a richly layered dial (hour ticks, gradient sweep,
moment marker, glow+core lanes) with the countdown living inside it, and panel-style cards.

Phase 8 ports that identity to the native **product windows** (main + mini). Per the parity
strategy, system surfaces keep the native idiom: the Settings window stays a system-styled
macOS form, the menu-bar dropdown a system menu.

## Goals

- One Swift source of truth for the DayView palette (dark + light), mirroring
  `DayViewTheme` verbatim; `BusyPalette.swift` absorbed and deleted.
- The full JVM dial in `DayRingCanvas`: track, goal halo, hour ticks, ratio-based accent
  with the rotated sweep-gradient, moment marker, finished rest state, glow+core busy lane.
- The countdown (headline + seconds + net lines) moves inside the dial in both windows.
- The `glow → ink` radial window background and DayView panel cards in both windows.
- One tiny `:core` addition: `TodaySnapshot.hasStarted`.

## Non-Goals

- Focus session bands/arcs and the detour lane on the dial (presence and detours phases —
  the dial's draw order leaves their slots).
- Scrub marker (dropped for macOS).
- Restyling the Settings window or the menu-bar dropdown (system surfaces stay native).
- The JVM's structured interior numerals (stacked H/h/MM composition with per-element
  sizing) — the native interior renders the existing Kotlin `dayStatus` string styled
  large; a finer-grained numeral layout can come later if wanted.
- Typography beyond weights/sizes (no custom fonts; the JVM uses system fonts too).
- i18n, font-scale (dropped), animations beyond what already exists.

## Decisions (from brainstorming)

1. **Product windows only** — main + mini get the identity; Settings and the dropdown stay
   system-styled (native idiom for system surfaces, brand for product surfaces).
2. **Countdown inside the dial** — the signature DayView composition.

## Architecture

### `DayViewPalette.swift` (new; replaces `BusyPalette.swift`)

A struct mirroring `DayViewColors` with the exact `DayViewTheme.kt` values:

| Field | Dark | Light |
|---|---|---|
| ink | `0B0D12` | `F4F2EC` |
| panel | `14171E` | `FFFFFF` |
| cloud | `F3F1EB` | `19201D` |
| muted | `8B909B` | `68716D` |
| mint | `78E6BD` | `168866` |
| amber | `FFB86B` | `B76218` |
| red | `FF7272` | `C74646` |
| glow | `171B22` | `DCEAE3` |
| overlay | white | `16211D` |
| busy[6] | sky `6EC6FF`, teal `6FD8C9`, periwinkle `8AA6FF`, violet `B39DFF`, slate cyan `7FB4CC`, steel `9FC0E8` | `2C6FA6`, `2E8B84`, `3F52A8`, `6A4FA8`, `34738A`, `4E6E96` |

Accessed as `DayViewPalette.current(for: colorScheme)` (static dark/light instances) — the
effective scheme already honors the `themeMode` setting through `preferredColorScheme`.
The `detours` list is NOT ported yet (detours phase brings it).

### `:core`: `hasStarted`

`TodaySnapshot` gains `hasStarted: Boolean` (from the existing `DayProgress.hasStarted`).
The dial needs it twice: before the day starts the ring is a full, uniform circle (a sweep
gradient on 360° would show its seam), and the moment marker only exists once started.
Pinned by one `DayViewSessionTest` addition (before-start fixture → `false`, running
fixture → `true`). No other Kotlin change.

### `DayRingCanvas` v2 — the full dial

Signature grows to carry the dial state:

```swift
DayRingCanvas(
    momentAngleDegrees: Double,
    remainingRatio: Double,
    isFinished: Bool,
    hasStarted: Bool,
    hasGoal: Bool,          // goalTitle non-blank || goalHasDeadline (derived by callers)
    busyArcs: [BusyArcSnapshot] = [],
    lineWidth: CGFloat = 18,
    inset: CGFloat = 40
)
```

Draw order and constants, verbatim from `DayViewTodayScreen.kt` (JVM references noted):

1. **Track:** full circle, `overlay` at 0.075, `lineWidth`, round caps.
2. **Goal halo** (when `hasGoal`): centered radial gradient `amber` 0.10 → clear, radius
   30% of the dial's min dimension (JVM `:1211`).
3. **Hour ticks:** 24 lines every 15°, from the outer edge inward — majors every 6th
   (90°): length 10 pt, `overlay` 0.28; minors 5 pt, `overlay` 0.12; 1 pt stroke
   (JVM `:1248`).
4. **Remaining sweep** (when `remainingRatio > 0` and not finished): accent = `amber` when
   `remainingRatio < 0.2`, else `mint`.
   - `hasStarted`: the rotated sweep-gradient trick (JVM `:1273`) — rotate the drawing by
     the moment angle so the gradient seam coincides with the arc's start; gradient
     `accent → accent@0.62`.
   - not started: uniform full circle in `accent` (no gradient — no leading edge to
     justify it, and the seam would show).
5. **Moment marker** (`hasStarted && !isFinished`): at the moment angle on the arc radius —
   `amber` halo 0.2 alpha @ 0.68×lineWidth radius, `amber` dot @ 0.4×, white 0.45 highlight
   @ 0.1× offset up-left by 0.1×lineWidth (JVM `:1296`).
6. **Finished rest state** (`isFinished`): instead of a sweep, the calm neutral circle —
   `overlay` at 0.16, plus the resting marker parked at 12 o'clock (`overlay` 0.12 @
   0.6×lineWidth over `muted` @ 0.34×) (JVM `:1320`).
7. **Busy lane:** upgraded to the JVM's two-pass **glow+core** — glow `busy[i]` at 0.16,
   stroke 0.7×lineWidth; core at 0.92, stroke 0.42× — same lane radius
   (−`lineWidth`×0.95) and hover geometry as today (JVM `:1356`). This closes the
   "single-pass reads heavier" parking-lot note.

The shared statics (`busyRadiusFactor`, `busyWidthFactor`) stay; the hardcoded `40`/`18`
in `RingView.busyArcHit` are promoted to `DayRingCanvas.defaultInset`/`defaultLineWidth`
statics (closing the other parking-lot note).

### Composition — countdown inside the dial

`RingView` and `MiniView` overlay the dial's interior (ZStack center) with:

- the `dayStatus` headline — `cloud`, light weight, monospaced digits, sized per window
  (main ~40 pt, mini ~24 pt as today);
- the seconds line and net line beneath it — `muted` captions, unchanged gating
  (`secondsLabel`/`netTimeLabel` non-empty).

The text below the dial disappears. Hover captions float above the dial as today.

### Backgrounds and cards

- **Window background:** `RadialGradient(glow → ink)` full-bleed on both product windows'
  roots (`.ignoresSafeArea()` so it runs under the title bar), mirroring the JVM's
  `Brush.radialGradient(glow, ink)`.
- **Cards:** the main window's two `GroupBox`es and the mini's two quaternary cards become
  DayView panels: `panel` background, 15 pt corner radius, 1 pt border `overlay` at 0.06.
  Section headers as small bold caps with letter spacing — Focus in `amber`, Long-term
  goal in `mint` (JVM section color roles). Body text `cloud`, secondary text `muted`.
- **Controls:** native controls, palette-tinted: Start focus / Relaunch / Progressed in
  `amber`, Completed in `mint`, Stop / destructive in `red`, Resume later `muted` — the
  closure buttons keep `.bordered`. Text fields keep native styles (they adapt to the
  scheme).
- The mini's expand glyph and the main window's compact button become `muted`.

### What stays untouched

`SettingsView`, `MenuBarContent`, `WindowVisibility`, the bridge (beyond `hasStarted`),
all behavior. This is a presentation-only phase over existing data.

## Data flow

```
snapshot (existing fields + hasStarted)
  -> RingView/MiniView derive hasGoal; pass dial state to DayRingCanvas
  -> DayViewPalette.current(for: colorScheme) colors every surface
themeMode -> preferredColorScheme -> colorScheme -> palette variant
```

## Testing / done criteria

- **`:core:jvmTest`:** `hasStarted` false before the window opens, true during the day
  (fixture with a not-yet-started window vs. the running fixture).
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test,
  both windows, light AND dark:
  - ink/glow background, panel cards with amber/mint headers;
  - dial: ticks visible, mint gradient sweep with the bright leading edge at the marker,
    amber marker with highlight; accent flips to amber under 20% remaining (adjust the day
    window in Settings to force it); finished day → calm neutral ring with the resting
    marker (set the window to end in the past);
  - before-start state (window starting later today) → full uniform ring, no marker;
  - countdown + seconds + net lines centered in the dial;
  - busy arcs render glow+core and hover still works (labels, 5° margin);
  - goal halo appears when a goal is set, disappears when cleared;
  - Settings and the menu dropdown look unchanged (system-styled).

## Risks

- **Sweep-gradient rotation in SwiftUI** — `AngularGradient` is canvas-anchored like
  Compose's `sweepGradient`; the rotation must be applied to the drawing context (or the
  gradient's start/end angles offset by the moment angle). Verified visually in the smoke
  test; the fallback is offsetting the gradient stops by the moment angle instead of
  rotating the context.
- **Interior text vs. small mini sizes** — at the mini's 200×300 minimum the interior
  must not overflow the dial; the mini keeps its smaller type ramp and the goal card's
  height gate already guards the worst case. Checked in the smoke test.
- **Light-mode contrast** — the light palette is the JVM's own (shipped, proven); no new
  color decisions are made in this phase.

## Roadmap after this phase (context only)

Per the parity checklist: detours (brings the `detours` palette + outer lane), then
presence/on-goal (focus bands/arcs on the dial), and onward to cutover.
