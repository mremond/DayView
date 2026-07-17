# macOS Native — Detours: capture + tally + list (Path B, Phase 9a)

## Context

The native macOS app (Path B; phases 1–8 merged) has no detours. On the Compose/JVM app a
**detour** makes visible what pulled you off the path: declared by hand — a motif and an
approximate duration — from a "+ Detour" affordance under the dial, with recent motifs
offered as one-tap chips. Each episode is drawn as a colored body on the ring, with a
per-source tally under the dial and a daily total below the countdown; a list lets you
rename, adjust, delete, or add after the fact. Detours are purely informational — they never
change the countdown or net time — and are stored locally for the current day only.

All the model is in `:core`: `DetourEpisode`/`DetourSource`/`DetourBody`, the projections
`detourSourcesState`/`detoursTotalToday`/`detourBodiesState`, `recentDetourCategories`, and
the mutators `addDetour`/`addDetourEpisode`/`updateDetour`/`removeDetour`/
`restoreLastRemovedDetour`/`forgetRecentDetourCategory`.

Phase 9 ports detours in two increments (the 7a/7b pattern). **9a (this spec):** capture,
the per-source tally, the daily total, and the edit list — the interaction-heavy half, no
ring drawing. **9b (later):** the colored bodies on the ring's outer lane + hover.

## Goals

- Bridge: detour sources, daily total label, recent categories, and the day's episode list
  on the snapshot; capture/edit/remove/restore/forget actions.
- A "+ Detour" capture sheet (motif + recent chips + approximate duration + description).
- The per-source tally row under the dial and the daily total below the countdown.
- An edit list: rename/adjust/delete/undo/add-after-the-fact.

## Non-Goals (deferred)

- **9b:** the colored bodies on the ring + hover.
- The live running-detour timer (`startOpenDetour`/`stopOpenDetour`) — declare-after-the-fact
  is the primary path; the running timer is a later addition.
- Off-window detour tagging (`detoursOffWindowTotalToday`, the "off window" tag) — a JVM edge;
  port with 9b or later.
- Mini-window detours (the JVM mini has none, matching the busy-arc parity).
- Any `:core` controller change — the existing mutators are used as-is.

## Decisions (from brainstorming)

1. **Split 9a/9b** — capture + tally + list first; ring bodies + hover second.
2. **Declare-after-the-fact only** — `addDetour(category, durationMinutes, description)`; the
   running timer is deferred.

## Architecture

### Bridge (`TodaySnapshot`)

New primitives-only types and fields (conventions: `Long` numbers, `String` display text):

```kotlin
/** One distraction source for the tally row. */
data class DetourSourceSnapshot(
    val label: String,
    val colorIndex: Long,
    val totalLabel: String, // formatDurationHm(total), e.g. "1 h 05" / "25 min"
)

/** One declared episode, for the edit list (indexed to match detoursToday). */
data class DetourEntry(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val category: String,
    val description: String,
    val timeRangeLabel: String, // "09:00 – 09:15" via formatWallClock, honoring use24Hour
)

val detourSources: List<DetourSourceSnapshot>,
val detourTotalLabel: String,        // "Detours <total>" when any, else ""
val recentDetourCategories: List<String>,
val detours: List<DetourEntry>,      // == detoursToday, same order/indices
```

Mapped in `toTodaySnapshot` from `detourSourcesState`, `detoursTotalToday`,
`recentDetourCategories`, and `detoursToday`. `detourTotalLabel` uses the existing
`formatDurationHm`; `timeRangeLabel` uses the existing `formatWallClock(hour, minute,
use24Hour)` (the `use24Hour` already threaded in 7b), start and end converted from the exact
`Instant`s. `detourSources`/`detours` are empty unless there are episodes today (the
controller's day-gating handles staleness).

`DayViewSession` actions (each delegates to the existing controller method; snapshots update
through the normal `stateFlow` emission):

```kotlin
fun addDetour(category: String, durationMinutes: Int, description: String)
fun updateDetour(index: Int, startEpochMillis: Long, endEpochMillis: Long, category: String, description: String)
    // -> controller.updateDetour(index, DetourEpisode(Instant.fromEpochMilliseconds(start), ...end, category, description))
fun removeDetour(index: Int)
fun restoreLastRemovedDetour()
fun forgetRecentDetourCategory(category: String)
```

`TodayModel` mirrors each one line.

### Palette — the detours list

`DayViewPalette` gains the `detours` color list (deferred from Phase 8), verbatim from
`DayViewTheme.kt`:

- Dark: amber `FFB86B`, gold `E7CE6B`, coral `F2856D`, rose `E58FB6`, plum `B48EE0`,
  sand `D9B08C`.
- Light: `B76218`, `8F7A1C`, `B0492F`, `A34D74`, `6E4AA3`, `8A6844`.

Plus `detourColor(_ index: Int) -> Color` (safe modulo), used by the tally chips (9a) and
the ring bodies (9b).

### UI (`RingView`, main window)

- **Daily total:** `detourTotalLabel` as a muted secondary line joining the seconds/net
  lines in/under the dial (Phase 8 composition). Gated on non-empty.
- **Tally row:** a horizontal wrap of per-source chips under the dial — each a `detourColor`
  dot + `label` + `totalLabel`. Tapping a chip (or the row) opens the edit list. Hidden when
  `detourSources` is empty.
- **"+ Detour" button:** under the dial → the **capture sheet**:
  - "What pulled you off the path?" prompt; motif `TextField` (placeholder "E.g. unexpected
    call");
  - recent-category chips from `recentDetourCategories` — tap fills the motif;
  - "Approximate duration": segmented picks `5 / 15 / 30 / 45 / 60` min, default 15;
  - optional description `TextField`;
  - Cancel / Add — Add disabled when the motif is blank → `addDetour(motif, minutes,
    description)`; sheet dismisses.
- **Edit list sheet** ("Today's detours"):
  - rows: `category` · `timeRangeLabel` · duration (from the entry); empty state "No detours
    declared today.";
  - each row edits via a per-row sheet reusing the capture fields plus start/end adjust →
    `updateDetour(index, ...)`, and deletes → `removeDetour(index)`;
  - after a delete, an **Undo** affordance → `restoreLastRemovedDetour()`;
  - an "Add a detour" button opens the capture sheet for a retroactive entry;
  - a recent-chip context action (long-press / right-click) → `forgetRecentDetourCategory`.

Main window only. No ring bodies (9b).

## Data flow

```
controller (detoursToday, recentDetourCategories, detourSourcesState, detoursTotalToday)
  -> toTodaySnapshot(use24Hour) -> detourSources / detourTotalLabel / recentDetourCategories / detours
  -> RingView: daily-total line, tally row, capture sheet chips, edit list
capture sheet Add  -> TodayModel.addDetour -> controller.addDetour -> stateFlow -> snapshot
edit row save/delete/undo -> updateDetour / removeDetour / restoreLastRemovedDetour
```

## Testing / done criteria

- **`:core:jvmTest`** (`DayViewSessionTest`, fixture instant + a session):
  - `addDetour("Call", 15, "")` → snapshot `detourSources` has one entry (label "Call",
    `totalLabel` matching `^\d+ h \d{2}$|^\d+ min$`), `detourTotalLabel` starts with
    "Detours ", `detours` has one `DetourEntry` whose `timeRangeLabel` matches the
    `formatWallClock` output for the fixture (host-TZ-portable exact assertion), and
    `recentDetourCategories` contains "Call";
  - a second `addDetour("Call", 30, "")` → the source's total grows, `detours` has two
    entries;
  - `removeDetour(0)` → one entry left; `restoreLastRemovedDetour()` → two again;
  - `forgetRecentDetourCategory("Call")` → `recentDetourCategories` no longer contains it;
  - `updateDetour(0, start, end, "Meeting", "")` → the entry's category becomes "Meeting".
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test:
  "+ Detour" → declare (motif + recent chip + 15 min) → the daily total appears below the
  countdown and a tally chip under the dial; open the list, edit a motif, delete one (undo
  restores it), add one after the fact; the countdown/net time are unchanged by any of it.

## Risks

- **Index stability across edits** — `updateDetour`/`removeDetour` take the `detoursToday`
  index; the snapshot's `detours` must stay in that exact order (it mirrors `detoursToday`).
  The controller re-sorts by start on commit, so the list UI must re-read indices from the
  fresh snapshot after each mutation rather than caching them. Called out for the list
  implementation.
- **Sheet state vs. snapshot** — the capture/edit sheets hold local draft `@State` (a form
  being filled), unlike the settings pattern; on Add/Save they call the bridge and dismiss.
  This is correct (a modal draft is not persisted state); the tally/total/list stay
  snapshot-bound.

## Roadmap after this phase (context only)

9b: `detourBodiesState` → a primitives-only arc list on the snapshot; the outer detour lane
on `DayRingCanvas` (glow+core, mirror of the busy lane) + hover motif/times. Then
presence/on-goal, and onward to cutover.
