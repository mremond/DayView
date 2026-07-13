# Cap daily obligations at three added-per-day

## Problem

"Today's obligations" is meant to be a small, committed-up-front plan (at most
`MAX_PLANNED_OBLIGATIONS = 3`). Today the cap is enforced only against the *active*
list: tapping **DONE** converts an obligation into a detour episode and removes it from
the list, dropping the count. The freed slot lets the user keep piling on new
obligations through the day, which defeats the "commit your must-dos, then execute"
intent.

## Rule

Adding is allowed only while `active + completedToday < MAX_PLANNED_OBLIGATIONS`.

- **DONE** consumes a slot permanently for the day (the motif is remembered as completed).
- **✕ delete** frees a slot (removing a mis-typed or abandoned obligation is un-planning,
  not completing), so it never counts toward the cap.
- The completed tally is day-scoped and resets at local-day rollover, exactly like the
  active list.

Worked example: add A, B, C (3/3). Complete A → active {B, C}, completed {A}, still 3/3,
add field hidden. Delete B with ✕ → active {C}, completed {A}, now 2/3, add field
returns and one more may be added.

## Approach

Track a **second day-scoped list of completed motifs** parallel to the active list, under
the existing `plannedObligationsDayKey`. The cap counts both lists.

Rejected alternatives:

- *Scalar `completedCount: Int`.* Cheaper to store, but syncing a day-scoped counter
  needs a new versioned-int plus a merge rule whose cross-device semantics (max? sum?)
  are genuinely ambiguous, and it discards the motif text so completed obligations could
  never be surfaced. A list reuses the existing `DayScoped<String>` sync machinery with
  no new merge logic.
- *Derive completed from detours.* Completing already spawns a detour carrying the motif
  as its description, but detours are editable/removable and carry no provenance flag.
  Fragile.

## Changes

### 1. Data model
- `DayPreferencesSnapshot`: add `plannedObligationsCompleted: List<String> = emptyList()`.
- `DayViewUiState`: add the same field plus
  - `plannedObligationsCompletedToday` — gated on `plannedObligationsDayKey == dayKeyOf(dayNow)`, mirroring `plannedObligationsToday`.
  - `plannedObligationSlotsUsed = plannedObligationsToday.size + plannedObligationsCompletedToday.size`.
- `coerced()`, `toUiState()`, `withPersisted()`, `toSnapshot()` in `DayViewController.kt`
  carry the new field. `coerced()` sanitizes/caps it the same way as the active list.

### 2. Pure logic (`PlannedObligations.kt`)
- `addPlannedObligation(current, motif, alreadyUsed: Int = 0)`: cap check becomes
  `current.size + alreadyUsed >= MAX_PLANNED_OBLIGATIONS`. Default `0` keeps existing
  callers/tests compiling.
- Add `completePlannedObligation(active, completed, motif)` (pure) returning the updated
  `(active, completed)` pair: drops case-insensitive matches from `active`, appends the
  sanitized motif to `completed`.

### 3. Controller (`DayViewController.kt`)
- `addPlannedObligation(motif)` passes `plannedObligationsCompletedToday.size` as
  `alreadyUsed`.
- `completePlannedObligation(...)`: after spawning the detour, update both lists via the
  new pure helper and commit them day-tagged (extend `commitPlannedObligations` to take
  both lists, or add a sibling commit).
- `removePlannedObligation(motif)` unchanged → still frees a slot.

### 4. Persistence (`DayPreferencesStore.kt`)
- New key `PLANNED_OBLIGATIONS_COMPLETED = "planned_obligations_completed"`
  (`stringPreferencesKey`), encoded/decoded with the existing
  `encodePlannedObligations` / `decodePlannedObligations` helpers.

### 5. Sync (`SyncDocument.kt`, `SyncMapper.kt`, `SyncMerge.kt`)
- `SyncDocument`: add `plannedObligationsCompleted: DayScoped<String>`.
- `buildDocument`: build it with `buildDayScoped` off `plannedObligationsDayKey` and the
  completed list (same pattern as `plannedObligations`).
- `applyDocument`: read `dayKey` (already taken from `plannedObligations.dayKey`; the two
  share a day) and map non-deleted items back to the completed list.
- `merge`: one `mergeDayScoped(...)` line.
- **Schema version stays 1.** `ignoreUnknownKeys = true` plus a default-empty field makes
  new→old and old→new documents back-compatible; no migration needed.

### 6. UI (`PlannedObligationsUi.kt`, `DayViewTodayScreen.kt`, strings)
- `PlannedObligationsChip` count = `plannedObligationSlotsUsed` (so it reads e.g. `3/3`
  after two are done — honest about remaining capacity).
- `PlannedObligationsContent` takes a `slotsUsed: Int` (or `canAdd: Boolean`); `atCap`
  becomes `slotsUsed >= MAX_PLANNED_OBLIGATIONS`. When the add field is hidden while the
  active list is not itself full, render a one-line muted hint explaining the day's slots
  are spent.
- New string `planned_obligations_cap_reached` in both
  `values/strings.xml` and `values-fr/strings.xml` (e.g. EN "All 3 of today's
  obligations are used." / FR "Les 3 obligations du jour sont utilisées.").
- Wire `state.plannedObligationSlotsUsed` from `DayViewTodayScreen` into the chip and
  dialog content.

### 7. Rollover
No extra work: the completed list is day-scoped by `plannedObligationsDayKey`, so
`plannedObligationsCompletedToday` reads empty on a new day and the cap resets.

## Testing
- `PlannedObligationsTest`: cap counts completed; ✕ frees a slot; re-add after ✕ succeeds;
  no add once 3 are done; `alreadyUsed` default preserves old behavior; the pure
  `completePlannedObligation` helper moves the motif and is case-insensitive.
- `DayViewControllerTest`: completing increments slots-used and blocks the next add; day
  rollover clears the completed tally; ✕ after a completion re-opens a slot.
- `SyncDocumentJsonTest` + merge tests: completed list round-trips through JSON; a
  document missing the field decodes to empty (back-compat); merge unions completed
  motifs across devices and a motif completed on one device tombstones in the other's
  active list.
- `PlannedObligationsUiTest`: add field hidden and hint shown when slots are spent; chip
  reflects slots-used.

## Non-goals
- Showing the completed obligations (struck-through) in the dialog. The list is stored
  and could support this later, but this change only gates adding.
- Changing `MAX_PLANNED_OBLIGATIONS` or making it configurable.
