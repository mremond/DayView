# Detour category + description — design

## Goal

A detour should carry a **category** and an optional **description**.

Today a `DetourEpisode` has a single free-text `motif` that does double duty: it is
both the label shown and the key that groups episodes into colored "sources" under the
ring. This change splits that into an explicit, honest model:

- **category** — free text with remembered recent categories; the grouping/coloring key.
  Required. This is today's `motif` renamed and repurposed.
- **description** — optional single-line note (≤200 chars). Adds detail to a specific
  episode without affecting grouping.

A detour then reads like "Réseaux sociaux — scrolling Twitter about the election".

Out of scope (possible later work): a dedicated tag/category editor for managing the
category list. Categories stay free-text-with-suggestions for now.

## Model (`Detours.kt`)

```kotlin
data class DetourEpisode(
    val start: Instant,
    val end: Instant,
    val category: String,        // was `motif` — grouping/coloring key (required)
    val description: String = "" // new — optional note
) {
    val duration: Duration get() = end - start
}
```

### Rename: `motif` → `category`

The grouping key is renamed everywhere it means "the grouping key":

- `DetourEpisode.motif` → `.category`
- `sanitizeDetourMotif` → `sanitizeDetourCategory`
- `recentDetourMotifs` → `recentDetourCategories`
- `pushRecentDetourMotif` → `pushRecentDetourCategory`
- `removeRecentDetourMotif` → `removeRecentDetourCategory`
- `MAX_RECENT_DETOUR_MOTIFS` → `MAX_RECENT_DETOUR_CATEGORIES`
- `DetourBody.motif` → `.category`
- `encodeRecentDetourMotifs`/`decodeRecentDetourMotifs` → `...Categories`
- `detourEpisodeAt(..., motif, ...)` → `detourEpisodeAt(..., category, description, ...)`

Grouping and colors (`detourSources`, `detourBodies`, `sourceKey`) key off `category`
exactly as they key off `motif` today. `DetourSource` is unchanged in shape.

### Sanitizers

Extract a shared generic and give each field its own rule:

```kotlin
/** Single-line, trimmed, length-bounded. */
fun sanitizeLabel(raw: String, maxLen: Int): String =
    raw.replace("\n", " ").replace("\r", " ").trim().take(maxLen).trim()

/** Category is a grouping key stored as the 3rd CSV field: no commas (they'd bleed). */
fun sanitizeDetourCategory(raw: String): String =
    sanitizeLabel(raw, 60).replace(",", " ").trim()

/** Description is the last CSV field: commas allowed, newlines stripped. */
fun sanitizeDetourDescription(raw: String): String =
    sanitizeLabel(raw, 200)
```

`PlannedObligations` currently reuses `sanitizeDetourMotif`. It switches to
`sanitizeLabel(·, 60)` — identical behavior to what it has today (no comma stripping,
which would be a behavior change for obligations).

## Persistence & codec (`Detours.kt`)

### Line format and the legacy-corruption catch

Today each episode serializes as `start,end,motif` with `split(",", limit = 3)`, so
`motif` is last and commas inside it survive. Naively appending `description` as a 4th
field would silently corrupt any existing stored detour whose `motif` contains a comma
(the old `s,e,a,b` would decode as category `a`, description `b`).

Guard against it with a version marker on the whole encoded blob:

- **Encode** `start,end,category,description` per line (category comma-stripped by
  sanitizer; description last, commas allowed, newlines stripped). Always emit all four
  fields — empty description → trailing empty field. Prepend a single sentinel line
  `@2` to the blob.
- **Decode**:
  - If the blob's first line is the `@2` sentinel: parse the remaining lines as four
    fields (`split(",", limit = 4)`), `category = parts[2]`, `description = parts[3]`
    (or `""`). Skip blank/malformed/inverted/category-less lines, as today.
  - Otherwise (legacy blob, no sentinel): parse every line with the old 3-field rule,
    mapping the old `motif` into `category` and leaving `description` empty. This
    preserves grouping and colors for all historical detours, comma or not.

This lives entirely in `encodeDetours`/`decodeDetours`, so `DayHistoryCodec` (which
calls them) inherits it with no change.

### Recent categories

`encodeRecentDetourCategories`/`decodeRecentDetourCategories` are the renamed recent
codecs; format is unchanged (one label per line).

### Stable key strings

The persisted preference **key strings** stay identical — `detours` and
`detour_recent_motifs` — even though the Kotlin identifiers rename
(`detoursKey`, `detourRecentMotifsKey` → `detourRecentCategoriesKey`). No stored data is
lost; existing recent suggestions carry over. (Renaming the key *string* would silently
drop them, so we deliberately keep it.)

## State & controller (`DayPreferences.kt`, `DayPreferencesStore.kt`, `DayViewController.kt`)

- `DayPreferences.recentDetourMotifs` → `recentDetourCategories` (default empty). Store
  read/write uses the renamed codecs; key strings unchanged (above).
- `DayViewUiState.recentDetourMotifs` → `recentDetourCategories`; derived detour state
  (`detourBodiesState`, `detourSourcesState`, totals) is unchanged in shape.
- Controller mutators carry the new field:
  - `addDetour(category: String, durationMinutes: Int, description: String = "")`
  - `addDetourEpisode(episode)` — sanitizes both `category` and `description`; rejects
    blank category or non-positive duration (as today).
  - `updateDetour(index, episode)` — same sanitize/reject rules.
  - `forgetRecentDetourMotif` → `forgetRecentDetourCategory`.
  - `commitDetours(..., pushCategory: String? = null)` pushes onto recent categories.
  - `promoteObligation(originalMotif, detourMotif, ...)` — parameters renamed to
    `originalObligation`, `detourCategory`; still creates a detour with empty description.

## UI (`DetoursUi.kt`, `DayViewTodayScreen.kt`)

- **Quick-capture dialog (`DetourCaptureContent`)**: the existing text field is relabeled
  **Catégorie / Category**; its suggestion chips are recent categories. Add an optional
  **Description** field (single-line `GoalTextField`) below the recent-category chips.
  Category stays required — the confirm (ADD) button is disabled while it is blank;
  description is always optional. `onConfirm` gains a `description` argument.
- **Edit / retroactive-add form (`DetourEditForm`)**: same — category field plus an
  optional description field, seeded from the episode being edited. SAVE disabled while
  category is blank.
- **List rows (`DetourListDialog`)**: when `description` is non-empty, render it as a
  muted line under the existing time-range line (single line, ellipsized).
- **Ring hover overlay (`DayViewTodayScreen`)**: under the category line, render the
  description in the muted style when non-empty.
- **Per-source tally (`DetourRow`)**: unchanged — grouped by category.

Description input is **single-line, ≤200 chars** (enforced by `sanitizeDetourDescription`
and matched by the codec's newline stripping).

### Test tags (`DayViewTestTags.kt`)

Add `DetourDescriptionField`. Existing `DetourMotifField` is renamed to
`DetourCategoryField` (tag string value may stay stable to minimize churn, but the
identifier renames for clarity).

## i18n (`values/strings.xml`, `values-fr/strings.xml`)

- Rename detour "motif" strings to "category":
  - `detour_motif_label` → `detour_category_label` ("Category" / "Catégorie")
  - `detour_motif_placeholder` → `detour_category_placeholder`
  - `detour_forget_row_label`, `detour_forget_prompt` — reword "reason"/"motif" → category
- Add:
  - `detour_description_label` ("Description" / "Description")
  - `detour_description_placeholder` (e.g. "Optional detail" / "Détail (optionnel)")

The user-facing capture prompt (`detour_capture_prompt`, "What pulled you off the
path?") stays.

## Testing (TDD)

New / updated tests:

- **Codec** (`DetoursTest`): round-trip category + description; empty description;
  description containing commas; category with commas is stripped; **legacy blob**
  (no sentinel) decodes `motif` → `category` with empty description, including a legacy
  `motif` that contains a comma; malformed/blank/inverted lines still skipped.
- **Sanitizers**: `sanitizeDetourCategory` strips commas and caps at 60;
  `sanitizeDetourDescription` caps at 200 and strips newlines; `sanitizeLabel` generic.
- **Sources / bodies**: grouping still keyed by category; `DetourBody.category` and
  `.description` populated.
- **Prefs store** (`DayPreferencesStoreTest`): detours with descriptions round-trip;
  recent categories persist under the stable key.
- **History** (`DayHistoryCodecTest`, `DayHistoryRecordTest`): descriptions survive a
  history round-trip; legacy history decodes.
- **Controller** (`DayViewControllerTest`): `addDetour`/`addDetourEpisode`/`updateDetour`
  carry and sanitize description; blank category rejected; recent categories pushed.
- **UI** (`DetourCaptureTest`, `HistoryDayScreenTest`): capture enters a description and
  it reaches `onConfirm`; edit form seeds and saves description; list row and hover show
  the description when present; ADD/SAVE gated on category.
- Mechanical `motif` → `category` renames across existing detour tests.

## Files touched

- `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (capture-dialog `onConfirm`
  wiring: thread the new `description` argument through to `addDetour`)
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- `composeApp/src/commonMain/composeResources/values/strings.xml`
- `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Tests: `DetoursTest`, `DayPreferencesStoreTest`, `DayHistoryCodecTest`,
  `DayHistoryRecordTest`, `DayViewControllerTest`, `DetourCaptureTest`,
  `HistoryDayScreenTest`, and any other test referencing `motif`.
