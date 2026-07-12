# Planned obligations — a pre-declared detour queue (max 3)

## Context

DayView is deliberately **single-goal**: one global goal is the sun at the center, and
every stretch of time is classified as on-goal, neutral, or a **detour** (a hand-declared
episode off the path — see
[`Detours.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt) and
[the orbital detours design](2026-07-12-daily-detours-orbital-viz-design.md)).

What the model has no place for is a **legitimate but competing obligation**: the things
the owner *must* do today (work, for example) that are neither the main goal nor a
distraction. In the current model they only ever surface *after the fact*, as detours,
with nothing up front to keep them in check.

The insight from brainstorming: the "planned vs unplanned" distinction has value **at
planning time, not at visualization time**. A detour is already framed as a named pull,
not a fault — so once an obligation is done, it can simply *become* an ordinary detour.
The only new mechanism worth adding is a **voluntary cap** on how many such obligations
the owner allows themselves in a day.

## Goal

Let the owner pre-declare up to **3** obligations for the day — a small, day-scoped list
of motifs. Doing one logs it as an ordinary `DetourEpisode` (motif already filled) and
drops it from the list. The cap is the only forcing function: a guard-rail against the
erosion of the sun. Nothing else about detours, the ring, or focus changes.

## Validated decisions

- **A planned obligation is a motif only.** Free text, same sanitation as a detour motif
  (`sanitizeDetourMotif`: single-line, trimmed, ≤60 chars). No duration at declaration
  time — declaration stays light.
- **Hard cap of 3.** Adding a 4th is softly refused (the input disables at 3). The cap is
  fixed in code, not configurable — consistent with the "no settings in v1" stance of the
  clean-focus-sessions design.
- **Day-scoped, no carry-over.** The list is scoped with the existing `dayKeyOf`
  convention. At the day rollover it resets to empty. An obligation left undone leaves
  **no trace** — no carry-over, no debt.
- **Doing one = logging a detour.** Marking an obligation "done" creates a normal
  `DetourEpisode` via the existing capture flow, with the motif pre-filled; the owner
  confirms the approximate duration exactly as for any detour. The obligation then leaves
  the list.
- **Nothing new on the ring.** A planned detour, once done, is a detour like any other —
  no distinct color, shape, or "planned" marking, and no budget counter (the YAGNI option
  chosen during brainstorming). The list's whole value is up front.

## Data model and persistence

A planned obligation carries no timing of its own, so the day's list is just an ordered
list of motif strings plus the day key it belongs to. Following the `Detours.kt` pattern:

- Pure functions over `List<String>` (add with cap enforcement + sanitation, remove by
  index/value), mirroring the shape of `pushRecentDetourMotif` / `removeRecentDetourMotif`
  so the two families read alike.
- `encodePlannedObligations` / `decodePlannedObligations`: one sanitized motif per line
  (same newline-joined convention as `encodeRecentDetourMotifs`), capped on decode so a
  corrupted store can never exceed 3.
- Persisted in the DataStore preferences store alongside the detour keys, keyed together
  with the current `dayKeyOf` value; a stored day key that no longer matches the current
  day yields an empty list (the rollover reset, no migration needed).

`MAX_PLANNED_OBLIGATIONS = 3` sits next to `MAX_RECENT_DETOUR_MOTIFS`.

## UI

A compact section on the single-page home, near the existing detour capture:

- The ≤3 obligation motifs, each with a **"done"** affordance that opens the normal detour
  capture with the motif pre-filled (owner confirms duration, then it is logged and
  removed).
- An add field that **greys out at 3**.
- Empty state is quiet — an unobtrusive "obligations du jour" prompt, never nagging.

## Deferred (YAGNI, v1)

Pre-filled or estimated durations · carry-over to the next day · a "planned" marking or
color on the ring · a budget / within-budget counter · a configurable cap · ordering or
priorities among obligations · any effect on the countdown, net time, streak, or clean
sessions (planned obligations are purely a planning aid until the moment they become an
ordinary detour).

## Testing

Pure functions are covered without UI, following the `Detours.kt` test style:

- Add enforces the cap (a 4th is a no-op), sanitizes, and preserves order.
- Remove drops the right entry and is a no-op on a missing/blank motif.
- Encode/decode round-trips, tolerates malformed lines, and caps on decode.
- Day-key mismatch yields an empty list (rollover reset).
