# Daily detours — orbital visualization around the global goal

## Context

DayView already renders the *positive* side of the day on the ring: consumed time, busy
calendar arcs (net time), and intense-focus arcs built from on-goal presence
([`PresenceAccumulator.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/PresenceAccumulator.kt),
[`OnGoalState.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/OnGoalState.kt)).
What is missing is the opposite: a picture of the **detours** — the elements that pulled
the user off the path — that never loses sight of the final goal.

The metaphor chosen during brainstorming is **gravity**: the global goal is the sun at the
center; the day ring is the user's orbit around it; every distraction is a small celestial
body whose attraction the user had to escape to stay on course. A detour is not a fault —
it is a named object with a pull.

## Goal

Let the user declare detours by hand (no new monitoring) and draw each one as a small body
hooked just outside the day ring at the time it happened, colored by its source, with a
per-source tally under the dial. The global goal stays at the center, framed as the sun.

## Validated decisions

- **Detours are declared manually.** A detour is an episode with a *motif* (free text,
  e.g. "Appel client") and an approximate duration. No automatic detection, no new
  observation of the user's activity.
- **The fixed reference is the existing global goal** (title + deadline already stored).
- **Capture is quick, retouch is calm**: in-the-moment capture from the main screen, and
  an editable "today's detours" list to fix durations, rename, delete, or add a forgotten
  episode later.
- **Visualization is the "episode satellites" variant**: one body per episode at its time
  position. Explicitly deferred: attractor belt (one aggregated body per source on an
  outer orbit), live "escape" animation while a detour is running, end-of-day review
  reminder, non-main-screen surfaces, multi-day history.
- **Purely informational**: detours never affect the countdown, net time, or Focus.

## Data model and persistence

New common model, following the `FocusPresenceInterval` pattern:

```kotlin
data class DetourEpisode(val start: Instant, val end: Instant, val motif: String)
```

- Domain time uses `kotlin.time.Instant`/`Duration`; epoch millis appear only in the
  serialization boundary, as elsewhere since the Instant/Duration refactor.
- **Source identity**: the *source* of an episode is its motif normalized (trimmed,
  lowercase comparison). Same normalized motif ⇒ same source ⇒ same color. Display uses
  the casing of the source's earliest episode of the day.
- **Encoding**: one episode per line in a single preference string, formatted
  `startMillis,endMillis,motif`. The motif comes last and the decoder splits each line
  with limit 3, so commas inside motifs survive; newlines are stripped from motifs at
  capture time. Malformed lines are skipped on decode, matching `decodeFocusPresence`.
- **Day scope**: episodes belong to the current day only. The same day-key mechanism as
  `PresenceAccumulator` clears them on rollover; persisted state is restored at startup
  for the same day key.
- **Recent motifs**: a persisted most-recent-first list (deduped case-insensitively,
  capped at 10) survives across days and feeds the capture suggestions.

## Capture (main screen, shared code)

- A discreet "add a detour" affordance on the today screen opens the capture dialog.
- The dialog contains: a required single-line motif field, suggestion chips built from
  the recent-motifs list, and quick duration picks (5 / 15 / 30 / 45 / 60 min).
- Confirming creates an episode that **ends now and starts `duration` earlier**, clamped
  to the day window start, and pushes the motif to the recents list. Fine placement is
  not asked at capture time; it belongs to the retouch list.

## Rendering on the dial

- **Bodies**: one filled circle per episode, centered just outside the ring at the angle
  of the episode's midpoint (reusing the day-window angle mapping of the today screen).
- **Size**: radius linear in episode duration, clamped between a minimum and maximum
  (exact dp tuned at implementation; roughly 4–10 dp at the default window size).
- **Colors**: a warm 6-hue palette (amber first), theme-aware for light/dark. Hues are
  assigned to sources in chronological order of each source's earliest episode, cycling
  past 6. The assignment is recomputed from episode times, so it stays deterministic
  after retroactive edits.
- **Sun framing**: when a global goal is set, a soft warm halo is drawn behind the center
  goal title — subtle, non-animated, consistent with both themes.
- **Totals**: below the countdown, a "Détours · H h MM" line appears when the total is
  positive, next to the existing focused-time total. Below the dial, a tally lists up to
  the 3 heaviest sources with their color dots and cumulated durations, plus a "+n"
  overflow indicator. Final wording follows the app's existing UI language conventions.
- **Degenerate cases**: bodies and tally render even when no global goal is set (the
  center simply keeps its current content); overlapping bodies are accepted in v1
  (chronological draw order, no collision resolution).

## Interactions

- **Pointer platforms**: hovering a body shows a tooltip with motif, start–end times, and
  duration — the same mechanism as the calendar-arc hover. Clicking a body or the tally
  opens the day's detours list.
- **Android**: tapping the tally (or the list affordance) opens the list. No fine
  per-body tap targets on touch screens.

## "Today's detours" list (retouch)

A shared dialog/sheet listing the day's episodes chronologically — color dot, motif,
start–end, duration — with:

- edit (motif, duration, start time), delete, and retroactive add (time + duration +
  motif);
- immediate recomputation of sources, colors, totals, and the dial after any change.

## Out of scope for v1

Attractor belt view (S2), live escape scene (F3), end-of-day review prompt, mini window /
menu bar / Android widget surfaces, multi-day history and weekly statistics, any form of
automatic detour detection.

## Testing

- Encode/decode round-trip: motifs with commas, malformed lines, empty payload.
- Source normalization and color assignment order, including after a retroactive insert
  that changes which episode is a source's earliest.
- Geometry pure functions: midpoint angle within the day window, radius clamping.
- State logic: add / edit / delete recompute totals; day rollover clears episodes;
  recents list dedupes case-insensitively and caps at 10.
- Tally aggregation: per-source cumulation, top-3 cap, overflow count.
- Platform test suites follow the existing split (`testDebugUnitTest` + `desktopTest`).
