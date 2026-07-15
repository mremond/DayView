# Editable essentials label — design

## Goal

Let the user rename each active "incontournable" (planned obligation) in place, as long
as it is not yet done. There are at most three essentials per day. Completed essentials
stay read-only.

## Background

The essentials feature ("Incontournables du jour") stores state as two plain
`List<String>` on `DayViewState`, day-scoped:

- `plannedObligations` — active items (not done)
- `plannedObligationsCompleted` — done items

There is no per-item data class and no stable ID. **The label string is the item's
identity**, matched case-insensitively by remove/complete. Done vs. not-done is expressed
purely by which list the string sits in. The cap of three (`MAX_PLANNED_OBLIGATIONS`) and
all mutations funnel through `commitPlannedObligations(active, completed)` in
`DayViewController`, which does `state.copy(...)` then `persistState()` (persistence +
cross-device sync flow through unchanged, still just lists of strings).

Relevant files:

- Logic: `core/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt`
- State + controller: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- UI: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt`
- Screen wiring: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
- Top-level wiring: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`
- Existing editable-text component: `GoalTextField` in `DayViewTodayScreen.kt` (has an
  `onFocusLost` callback; already used for the essentials add field)

Because only the active list is made editable, the "editable only while not done"
requirement is satisfied structurally — completed items live in the separate
`plannedObligationsCompleted` list and are never rendered as fields.

## Decisions (from brainstorming)

- **Interaction:** always-editable field. Each active item renders directly as an
  editable text field (like the add field), committing when focus leaves the field.
- **Empty field on commit:** revert to the previous label. Clearing a field does **not**
  delete the item — the existing ✕ button remains the only deletion path.
- **Duplicate on commit:** if the new label case-insensitively equals another item
  (active *or* completed), reject and revert to the previous label. This prevents
  ✕/FAIT from acting on two identical items at once (identity is the string).

## Design

### 1. Pure logic — `PlannedObligations.kt`

Add a pure, testable function:

```kotlin
fun editPlannedObligation(
    active: List<String>,
    completed: List<String>,
    oldMotif: String,
    newLabel: String,
): List<String>?
```

Returns the new active list, or `null` when the edit must be rejected (the UI reverts to
the old label on `null`). Rules, in order:

1. `val sanitized = sanitizeLabel(newLabel, 60)` — same normalization/length cap as add.
2. If `sanitized` is blank → `null`.
3. If `sanitized` equals `oldMotif` (exactly) → `null` (nothing to change).
4. If `sanitized` case-insensitively matches any item in `active` other than the one being
   edited, or any item in `completed` → `null` (duplicate rejected).
5. Otherwise, replace the element at `oldMotif`'s position in `active` with `sanitized`
   (order preserved) and return the new list. Locate the element to replace using the same
   case-insensitive match already used by `matchesPlannedObligation`; if `oldMotif` is not
   found, return `null`.

Rationale for position-preserving replace: the three essentials have a stable visual
order the user relies on; an edit must not reorder them.

### 2. Controller — `DayViewController.kt`

Add:

```kotlin
fun editPlannedObligation(oldMotif: String, newLabel: String) {
    val updated = editPlannedObligation(
        active = state.plannedObligationsToday,
        completed = state.plannedObligationsCompletedToday,
        oldMotif = oldMotif,
        newLabel = newLabel,
    ) ?: return
    commitPlannedObligations(updated, state.plannedObligationsCompletedToday)
}
```

Mirrors the shape of `setGoalTitle` and reuses the existing `commitPlannedObligations`
funnel, so persistence and sync need no changes. On `null` (rejected edit), no state
change occurs and the UI is responsible for reverting the field.

### 3. UI — `PlannedObligationsUi.kt`

In `PlannedObligationsContent` (and the `PlannedObligationsDialog` wrapper), add an action
parameter:

```kotlin
onEdit: (String, String) -> Unit,   // (oldMotif, newLabel)
```

Replace the active-item `Text(motif)` (currently around line 111) inside
`obligations.forEach { motif -> Row(...) { ... } }` with a `GoalTextField`. Wrap each row
in `key(motif) { ... }` so per-row local state is keyed to the item identity:

```kotlin
obligations.forEach { motif ->
    key(motif) {
        var draft by remember(motif) { mutableStateOf(motif) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GoalTextField(
                value = draft,
                semanticLabel = /* existing motif label string resource */,
                placeholder = /* existing motif placeholder */,
                onValueChange = { draft = it },
                onFocusLost = {
                    if (draft != motif) onEdit(motif, draft)
                    draft = motif // revert to canonical value; see revert semantics below
                },
                modifier = Modifier.weight(1f)
                    .testTag(DayViewTestTags.PlannedObligationLabel /* new tag */),
            )
            // existing ✕ remove control (unchanged)
            // existing FocusActionButton FAIT (unchanged)
        }
    }
}
```

Revert semantics (single mechanism, no validation duplicated in the UI):

- On focus loss, if the draft differs from `motif`, the UI optimistically calls
  `onEdit(motif, draft)`. The pure function is the sole authority on accept vs. reject.
  Immediately afterward the UI sets `draft = motif`.
- **Accepted:** the active list changes and `motif` is replaced by the sanitized value.
  `key(motif)` disposes this row and recreates it under the new key, with
  `remember(newMotif)` seeding a fresh `draft` equal to the new value. The transient
  `draft = motif` write lands on the disposed node and has no visible effect — the new row
  shows the new label.
- **Rejected (blank, unchanged, or duplicate):** the active list is unchanged, `motif`
  keeps its old value, and the `draft = motif` reset restores the old label in the field.

Setting `draft = motif` unconditionally is safe precisely because an accepted edit changes
`motif` and forces row recreation via `key(motif)`, so the reset only ever "wins" on a
rejected edit. This keeps all validation in the pure `editPlannedObligation` function; the
UI only mirrors the committed list and never re-checks blank/duplicate rules.

A new test tag `PlannedObligationLabel` is added to `DayViewTestTags` for the editable
field.

### 4. Wiring

- `DayViewTodayScreen.kt`: add `editPlannedObligation` to the today-screen actions
  (around the existing `addPlannedObligation` / `completePlannedObligation` /
  `removePlannedObligation` fields, ~line 214), and pass it to the dialog (~line 435).
- `App.kt`: wire the new action to `controller.editPlannedObligation` (~line 564, next to
  the other essentials actions).

### 5. Tests

Pure logic — `core/src/commonTest/kotlin/fr/dayview/app/PlannedObligationsTest.kt`:

- Edit succeeds and preserves position (edit the middle of three; first/last unchanged).
- Blank new label → `null`.
- New label equal to old (exact) → `null`.
- New label case-insensitively duplicating another active item → `null`.
- New label case-insensitively duplicating a completed item → `null`.
- Sanitization applied (trims/normalizes, caps at 60).
- `oldMotif` not present → `null`.

UI — `composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt`:

- Typing in an active field then moving focus away reports `(oldMotif, newLabel)` via the
  `onEdit` callback.
- Completed items are not rendered as editable fields (no `PlannedObligationLabel` tag in
  the completed section).
- Clearing a field to blank and blurring reverts to the old label (field shows old value,
  no `onEdit` with a blank label commits state).

## Out of scope (YAGNI)

- No stable per-item ID; identity remains the string.
- No editing of completed essentials.
- No dedicated edit button / edit mode; fields are always editable.
- No reordering.
