# Longer detours — making leisure fit the off-goal spectrum

## Context

DayView already lets the user hand-declare **detours** and draws each as a small body
threaded on the day ring, colored by its source, with a per-source tally under the dial
([`Detours.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt),
[`DetoursUi.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt)).
The detour was framed as "the elements that pulled the user off the path", drawn as a
gravitational body whose pull the user had to escape.

Leisure — watching a series, reading — is not a separate concept. It is the **same** notion
of *time spent off the path toward the goal*, read as one neutral spectrum: at one end a
suffered interruption ("Appel client"), at the other a chosen rest ("Série"). The app never
labels which is which; the user reads it from the motif and where it falls on the ring. So
leisure is logged as a detour, using the existing model and capture — no new data type, no
category layer, no valence flag.

The problem is that leisure is typically **long** (a series or an evening of reading easily
exceeds an hour), and two caps block it today:

1. **Quick capture stops at 60 min.** `DETOUR_DURATION_CHOICES = listOf(5, 15, 30, 45, 60)`
   ([`DetoursUi.kt:186`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt)).
   From the quick-capture dialog you cannot pick more than one hour. The list editor allows
   up to 12 h, but only in 5-minute steps — reaching 3 h means ~30 taps, which is unusable.
2. **The ring saturates at 60 min.** `MAX_BODY_DURATION = 60.minutes`
   ([`Detours.kt:115`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt)).
   A 3 h series is drawn exactly the same size as a 1 h interruption, so even once logged the
   ring misrepresents where the time went.

The storage already supports up to 12 h: `addDetour`, `detourEpisodeAt`, and
`detourDefaultStartMinutes` all clamp with `coerceIn(1, 12 * 60)`. The limit is purely in the
capture UI and the render.

## Goal

Let a detour longer than an hour be captured in one gesture and be drawn honestly on the ring,
without touching the `DetourEpisode` model or adding any new concept.

## Validated decisions

- **Leisure is a detour, kept neutral.** No new type, no category, no valence tag. One color
  per motif (current behavior). "Suffered vs rest" stays a reading, never a stored attribute.
- **Volet A — reach long durations at capture.** Keep the short pills `5 · 15 · 30 · 45 · 60`
  and add a sixth "plus long" affordance that reveals `1 h 30 · 2 h · 3 h`. Durations of 60 min
  and above are labeled in hours via `formatDurationHm`. Fine 5-minute adjustment stays in the
  list editor.
- **Volet B — honest ring.** Raise `MAX_BODY_DURATION` from 60 min to **3 h (180 min)**, with a
  **square-root** size scale so a long leisure block reads clearly larger than a short
  interruption without a 3 h body dominating the ring. The cap and curve are tuning values,
  calibrated on-device.
- **The model is untouched.** `DetourEpisode`, encoding/persistence, per-source coloring,
  hit-testing, and the day-scoped lifecycle are unchanged.

## Out of scope (documented boundaries, not addressed here)

- **Morning truncation.** In quick capture (`addDetour`,
  [`DayViewController.kt:331`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt)),
  a detour that ends now and whose computed start falls before the window start is clamped to
  the window start (`if (start < windowStart && end > windowStart) start = windowStart`). A 3 h
  detour captured at 10:00 with an 08:00 window is silently shortened to 08:00–10:00. This is
  kept as-is — it protects the ring's domain — and documented as a known limit. Users who want
  the exact span can set it in the list editor.
- **Leisure outside the work window.** An episode whose midpoint falls outside the day window
  (e.g. a series at 21:00 with an 08:00–18:00 window) is stored and listed but **dropped from
  the ring** by `detourBodies` (midpoint-outside-window guard). This is the "evening rest" case.
  Showing off-window time would touch the whole day-window model and is a separate topic.

## Design

### Volet A — quick-capture duration reach

`DetourCaptureContent` ([`DetoursUi.kt:206`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt))
keeps its current row of short pills. A sixth pill ("plus long") toggles a revealed set of long
choices `[90, 120, 180]` minutes, shown as an additional row (or an expanded pill group) below
the short row so both stay reachable. Selecting any pill — short or long — sets
`durationMinutes` and shows as selected; selecting a long value keeps the "plus long" group
open so the current choice stays visible.

- Long durations are constants (e.g. `DETOUR_LONG_DURATION_CHOICES = listOf(90, 120, 180)`),
  parallel to the existing `DETOUR_DURATION_CHOICES`.
- Pill labels switch from the minutes chip (`detour_minutes_chip`) to an hours label for
  values `>= 60`, formatted with `formatDurationHm` so `90 → "1 h 30"`, `120 → "2 h"`,
  `180 → "3 h"`. A new string resource carries the "plus long" label.
- No change to `onConfirm`: it already forwards `durationMinutes` unchanged, and `addDetour`
  accepts up to 12 h.

The list editor's duration stepper (`DetourEditForm`) is unchanged — it already reaches 12 h.

### Volet B — ring size scale

In `detourBodies` ([`Detours.kt:123`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt)):

- `MAX_BODY_DURATION` becomes `180.minutes` (`MIN_BODY_DURATION` stays `5.minutes`).
- The size fraction changes from linear to a square-root curve over the
  `[MIN_BODY_DURATION, MAX_BODY_DURATION]` range, so growth is fast for short detours and
  gentle for long ones, still clamped to `0f..1f`. Concretely: normalize the duration into
  `0..1` across the range, then take its square root.

`hitTestDetourBody` already derives its angular tolerance from `sizeFraction`, so larger bodies
stay hoverable with no change. Body straddling (light outside / heavy inside the ring) also
follows `sizeFraction` and needs no change.

## Testing

- **`DetoursTest`** (commonTest): the size-fraction scale is monotonic and continues past
  60 min (a 90/120/180-min detour is larger than a 60-min one), saturates at the 3 h cap, and
  the square-root shape gives short detours a steeper slope. Existing coloring, source-tally,
  and midpoint-drop tests stay green.
- **`DetourCaptureTest`** (desktopTest): the "plus long" affordance reveals the long choices,
  and selecting `2 h` / `3 h` confirms with `durationMinutes` of 120 / 180.
- `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` green, no stderr.

## Non-goals

- No change to `DetourEpisode`, persistence encoding, or the day-scoped lifecycle.
- No category or valence model; no per-category color families.
- No new capture surface, no leisure-specific motif seeding, no wording rewrite of the detour
  concept.
- No change to the day-window model or to how off-window episodes are handled.
