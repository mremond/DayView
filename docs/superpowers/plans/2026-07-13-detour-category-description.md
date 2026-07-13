# Detour Category + Description Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each detour an explicit **category** (the grouping/coloring key, replacing today's `motif`) and an optional single-line **description**.

**Architecture:** Two phases. Phase A (Task 1) is a behavior-preserving rename of `motif` → `category` across the whole codebase plus extraction of a shared `sanitizeLabel` helper. Phase B (Tasks 2–6) adds the new `description` field — model, a version-marked CSV codec that decodes legacy blobs losslessly, controller threading, capture/edit UI, and list/hover display.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime, Compose resources (i18n), kotlin.test + Compose UI test (`runComposeUiTest`).

## Global Constraints

- Run before every commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — must pass with no errors or stderr.
- ktlint is enforced; run `./gradlew ktlintFormat` to auto-fix style before committing.
- Commit messages describe the change only. **Never** add a Claude/Anthropic/AI reference, a `Co-Authored-By: Claude` trailer, a "Generated with" footer, a test plan, or a verification section. **Never** reference internal docs under `docs/superpowers/`.
- **Persisted preference key strings must stay byte-identical** (`detours`, `detour_recent_motifs`) even as Kotlin identifiers rename — changing a key *value* silently drops stored user data.
- Category is required (empty category is rejected / disables the confirm button); description is always optional.
- Category ≤ 60 chars, commas stripped. Description ≤ 200 chars, single line (newlines stripped).
- Compose UI tests: never assert on `stringResource` text (unresolved under `runComposeUiTest`); drive by test tags and captured callback values.

---

## File Structure

- `Detours.kt` — domain: `DetourEpisode`, `DetourBody`, sanitizers, CSV codec, sources/bodies. **Most changes land here.**
- `PlannedObligations.kt` — switches its sanitizer call from `sanitizeDetourMotif` to the new generic `sanitizeLabel`.
- `DayViewController.kt` + `DayPreferences.kt` + `DayPreferencesStore.kt` — state field rename + description threading; codec calls; stable key strings.
- `DayHistoryRecord.kt` / `DayHistoryCodec.kt` — inherit description via `encodeDetours`/`decodeDetours` (no logic change; tests added).
- `DetoursUi.kt` — capture dialog, edit form, list rows: category relabel + description input/display.
- `DayViewTodayScreen.kt` — capture/list dialog wiring + ring-hover overlay description.
- `App.kt` — `addDetour` action lambda gains a description argument.
- `DayViewTestTags.kt` — `DetourMotifField` → `DetourCategoryField`, add `DetourDescriptionField`.
- `values/strings.xml`, `values-fr/strings.xml` — rename motif→category strings, add description strings.

---

## Task 1: Rename `motif` → `category` (no behavior change)

Pure mechanical rename plus extraction of a shared sanitizer. **No new field, no new behavior.** The suite must be green before and after with identical semantics. Persisted key *strings* stay the same.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Test: `DetoursTest.kt`, `DetourCaptureTest.kt`, `DayViewControllerTest.kt`, `DayPreferencesStoreTest.kt`, `DayHistoryCodecTest.kt`, `DayHistoryRecordTest.kt`, `HistoryDayScreenTest.kt`, `HistoryWeekScreenTest.kt`, `HistoryNavigationTest.kt`, `UiTestSupport.kt`, `DetoursTest.kt` and any other test referencing `motif`.

**Interfaces:**
- Consumes: nothing new.
- Produces (exact new names later tasks rely on):
  - `sanitizeLabel(raw: String, maxLen: Int): String`
  - `sanitizeDetourCategory(raw: String): String` (Task 1: `= sanitizeLabel(raw, 60)`, comma-strip added in Task 2)
  - `DetourEpisode(start, end, category)` — `category: String`
  - `DetourBody(..., category: String, ...)`
  - `detourEpisodeAt(dayReference, startMinutesOfDay, durationMinutes, category, timeZone = ...)`
  - `pushRecentDetourCategory`, `removeRecentDetourCategory`, `encodeRecentDetourCategories`, `decodeRecentDetourCategories`, `MAX_RECENT_DETOUR_CATEGORIES`
  - `DayViewUiState.recentDetourCategories`, `DayPreferences.recentDetourCategories`
  - Controller: `addDetour(category, durationMinutes)`, `forgetRecentDetourCategory(category)`, `commitDetours(episodes, pushCategory)`, `completePlannedObligation(originalObligation, detourCategory, durationMinutes, startMinutesOfDay)`
  - Test tags: `DayViewTestTags.DetourCategoryField`

- [ ] **Step 1: Extract `sanitizeLabel` and rename the detour sanitizer in `Detours.kt`**

Replace the current `sanitizeDetourMotif`:

```kotlin
/** Single-line, trimmed, length-bounded label. */
fun sanitizeLabel(raw: String, maxLen: Int): String =
    raw.replace("\n", " ").replace("\r", " ").trim().take(maxLen).trim()

/** Single-line, trimmed, bounded category; every capture and edit feeds through this. */
fun sanitizeDetourCategory(raw: String): String = sanitizeLabel(raw, 60)
```

- [ ] **Step 2: Rename the `DetourEpisode` / `DetourBody` field and every `Detours.kt` helper**

Apply these identifier renames throughout `Detours.kt` (mechanical):

| Old | New |
| --- | --- |
| `DetourEpisode(... val motif: String)` | `... val category: String` |
| `sanitizeDetourMotif` | `sanitizeDetourCategory` |
| `encodeRecentDetourMotifs` | `encodeRecentDetourCategories` |
| `decodeRecentDetourMotifs` | `decodeRecentDetourCategories` |
| `pushRecentDetourMotif` | `pushRecentDetourCategory` |
| `removeRecentDetourMotif` | `removeRecentDetourCategory` |
| `MAX_RECENT_DETOUR_MOTIFS` | `MAX_RECENT_DETOUR_CATEGORIES` |
| `DetourBody(... val motif: String ...)` | `... val category: String ...` |
| `detourEpisodeAt(..., motif: String, ...)` | `detourEpisodeAt(..., category: String, ...)` |

In `encodeDetours`/`decodeDetours`, `detourSources`, `sourceKey`, `detourBodies`, `detourEpisodeAt`, replace every `it.motif` / `episode.motif` / `motif` local with `category`. **Keep the 3-field CSV format unchanged for now** (`start,end,category`, `split(",", limit = 3)`).

- [ ] **Step 3: Point `PlannedObligations.kt` at the generic sanitizer**

In `PlannedObligations.kt`, replace both `sanitizeDetourMotif(motif)` calls and the `decodePlannedObligations` map with `sanitizeLabel(·, 60)`:

```kotlin
fun addPlannedObligation(current: List<String>, motif: String): List<String> {
    val clean = sanitizeLabel(motif, 60)
    if (clean.isEmpty() || current.size >= MAX_PLANNED_OBLIGATIONS) return current
    return current + clean
}

fun removePlannedObligation(current: List<String>, motif: String): List<String> {
    val clean = sanitizeLabel(motif, 60)
    if (clean.isEmpty()) return current
    return current.filter { it.lowercase() != clean.lowercase() }
}

fun decodePlannedObligations(encoded: String): List<String> = encoded.split("\n")
    .map { sanitizeLabel(it, 60) }
    .filter { it.isNotEmpty() }
    .take(MAX_PLANNED_OBLIGATIONS)
```

(Obligation parameter/variable names keep `motif` — that is the obligation's own vocabulary, unrelated to detour categories.)

- [ ] **Step 4: Rename controller + state identifiers**

In `DayViewController.kt`:
- `addDetour(motif: String, ...)` → `addDetour(category: String, ...)`; internal `clean` uses `sanitizeDetourCategory`; `DetourEpisode(start, end, clean)`; `commitDetours(..., pushCategory = clean)`.
- `addDetourEpisode`/`updateDetour`: `episode.copy(motif = ...)` → `episode.copy(category = sanitizeDetourCategory(episode.category))`; guards use `clean.category`.
- `forgetRecentDetourMotif` → `forgetRecentDetourCategory`; body uses `removeRecentDetourCategory` and `state.recentDetourCategories`.
- `commitDetours(episodes, pushMotif)` → `commitDetours(episodes, pushCategory)`; body uses `pushRecentDetourCategory` and `recentDetourCategories`.
- `completePlannedObligation(originalMotif, detourMotif, ...)` → `completePlannedObligation(originalObligation, detourCategory, ...)`; calls `addDetour(detourCategory, ...)` and `detourEpisodeAt(state.now, startMinutesOfDay, durationMinutes, detourCategory)`.
- Everywhere `recentDetourMotifs` appears in `DayViewUiState`, snapshot copy, and restore (lines ~73, ~484, ~575, ~595, ~629, ~655) → `recentDetourCategories`. Snapshot sanitize `it.copy(motif = sanitizeDetourMotif(it.motif))` → `it.copy(category = sanitizeDetourCategory(it.category))`. `recentDetourMotifs.take(MAX_RECENT_DETOUR_MOTIFS)` → `recentDetourCategories.take(MAX_RECENT_DETOUR_CATEGORIES)`.

In `DayPreferences.kt`: `recentDetourMotifs: List<String>` → `recentDetourCategories: List<String>`.

- [ ] **Step 5: Rename prefs-store identifiers, keep key strings stable**

In `DayPreferencesStore.kt`:

```kotlin
// In DayPreferenceKeys: keep the VALUE "detour_recent_motifs" for back-compat with stored data.
const val DETOUR_RECENT_CATEGORIES = "detour_recent_motifs"
```

```kotlin
private val detourRecentCategoriesKey = stringPreferencesKey(DayPreferenceKeys.DETOUR_RECENT_CATEGORIES)
```

- Write: `prefs[detourRecentCategoriesKey] = encodeRecentDetourCategories(snapshot.recentDetourCategories)`
- Read: `recentDetourCategories = decodeRecentDetourCategories(this[detourRecentCategoriesKey].orEmpty())`
- `detoursKey` / `DETOURS` and `detoursDayPrefKey` / `DETOURS_DAY` are unchanged.

- [ ] **Step 6: Rename UI identifiers and string-resource references**

In `DayViewTestTags.kt`:

```kotlin
const val DetourCategoryField = "detourCategoryField"
```

In `DetoursUi.kt` and `DayViewTodayScreen.kt`, rename: `recentMotifs` params/args → `recentCategories`; `initialMotif` → `initialCategory`; local `motif` states → `category`; `body.motif` → `body.category`; `episode.motif` → `episode.category`; `forgetDetourMotif` action → `forgetDetourCategory`; `pushMotif`/`onForget` labels unchanged in text. `DayViewTestTags.DetourMotifField` → `DayViewTestTags.DetourCategoryField`. Update the `onConfirm` param names `motif` → `category` (signature arity unchanged in Task 1). Update `completePlannedObligation(motif, confirmedMotif, ...)` call to `(motif, confirmedCategory, ...)`.

Update the resource imports/usages to the renamed keys (see Step 7): `detour_motif_label` → `detour_category_label`, `detour_motif_placeholder` → `detour_category_placeholder`.

In `App.kt`: `forgetDetourMotif = { controller.forgetRecentDetourMotif(it) }` → `forgetDetourCategory = { controller.forgetRecentDetourCategory(it) }`; `addDetour = { motif, durationMinutes -> ... }` → `{ category, durationMinutes -> controller.addDetour(category, durationMinutes) }`. If the actions holder field is named `forgetDetourMotif`, rename it to `forgetDetourCategory` in `DayViewTodayScreen.kt` too.

- [ ] **Step 7: Rename string resources (both locales)**

In `values/strings.xml`:

```xml
<string name="detour_category_label">Category</string>
<string name="detour_category_placeholder">E.g. unexpected call</string>
<string name="detour_forget_row_label">Forget this category</string>
<string name="detour_forget_prompt">Forget this category?</string>
```

In `values-fr/strings.xml`:

```xml
<string name="detour_category_label">Catégorie</string>
<string name="detour_category_placeholder">Ex. appel imprévu</string>
<string name="detour_forget_row_label">Oublier cette catégorie</string>
<string name="detour_forget_prompt">Oublier cette catégorie ?</string>
```

(Delete the old `detour_motif_label` / `detour_motif_placeholder` entries and the old `detour_forget_*` wording. Keep `detour_capture_prompt` unchanged.)

- [ ] **Step 8: Rename identifiers across all tests**

In every test file listed above, rename `motif` → `category` in `DetourEpisode(...)` constructions and assertions, `recentMotifs` → `recentCategories`, `DetourMotifField` → `DetourCategoryField`, `sanitizeDetourMotif` → `sanitizeDetourCategory`, `pushRecentDetourMotif`/`removeRecentDetourMotif` → `...Category`, `MAX_RECENT_DETOUR_MOTIFS` → `MAX_RECENT_DETOUR_CATEGORIES`, `recentDetourMotifs` → `recentDetourCategories`, `forgetRecentDetourMotif` → `forgetRecentDetourCategory`. Do **not** change the `onConfirm` arity yet (still `(category, duration, start)`); the `Triple` captures stay.

- [ ] **Step 9: Format, run the full suite**

Run:
```bash
./gradlew ktlintFormat
./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL, no test failures, no stderr. (All behavior is identical; only names changed.)

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "Rename detour motif to category"
```

---

## Task 2: Add the `description` field + finalize sanitizers

Add `description` to the model with a safe default and give the two fields their final sanitization rules. Compiles without touching call sites (default `""`).

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`

**Interfaces:**
- Consumes: `sanitizeLabel` (Task 1).
- Produces:
  - `DetourEpisode(start, end, category, description: String = "")`
  - `DetourBody(..., category, description, start, end)` with `description: String` field
  - `sanitizeDetourCategory(raw): String` — now also strips commas
  - `sanitizeDetourDescription(raw): String`
  - `detourEpisodeAt(dayReference, startMinutesOfDay, durationMinutes, category, description: String = "", timeZone = ...)`

- [ ] **Step 1: Write failing sanitizer + model tests**

Add to `DetoursTest.kt`:

```kotlin
// DetoursTest already defines: private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)
@Test
fun sanitizeDetourCategoryStripsCommasAndCaps() {
    // Comma → space (inputs without a trailing space avoid a double space).
    assertEquals("Reseaux sociaux", sanitizeDetourCategory("Reseaux,sociaux"))
    assertEquals(60, sanitizeDetourCategory("x".repeat(200)).length)
}

@Test
fun sanitizeDetourDescriptionStripsNewlinesAndCaps() {
    assertEquals("a b", sanitizeDetourDescription("a\nb"))
    assertEquals(200, sanitizeDetourDescription("y".repeat(300)).length)
    assertEquals("with, comma", sanitizeDetourDescription("with, comma")) // commas kept
}

@Test
fun detourEpisodeCarriesDescription() {
    val episode = detourEpisodeAt(t(0), 12 * 60, 15, "Slack", "reading threads")
    assertEquals("Slack", episode.category)
    assertEquals("reading threads", episode.description)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: FAIL (unresolved `description`, no comma strip).

- [ ] **Step 3: Implement the model + sanitizer changes**

```kotlin
data class DetourEpisode(
    val start: Instant,
    val end: Instant,
    val category: String,
    val description: String = "",
) {
    val duration: Duration get() = end - start
}

fun sanitizeDetourCategory(raw: String): String = sanitizeLabel(raw, 60).replace(",", " ").trim()

fun sanitizeDetourDescription(raw: String): String = sanitizeLabel(raw, 200)
```

Add `description` to `DetourBody`:

```kotlin
data class DetourBody(
    val angleDegrees: Float,
    val sizeFraction: Float,
    val colorIndex: Int,
    val category: String,
    val description: String,
    val start: Instant,
    val end: Instant,
)
```

In `detourBodies`, populate `description = sanitizeDetourDescription(episode.description)` alongside `category = sanitizeDetourCategory(episode.category)`.

Extend `detourEpisodeAt`:

```kotlin
fun detourEpisodeAt(
    dayReference: Instant,
    startMinutesOfDay: Int,
    durationMinutes: Int,
    category: String,
    description: String = "",
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): DetourEpisode {
    // ... unchanged start computation ...
    return DetourEpisode(
        start = start,
        end = start + durationMinutes.coerceIn(1, 12 * 60).minutes,
        category = sanitizeDetourCategory(category),
        description = sanitizeDetourDescription(description),
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
git add -A
git commit -m "Add optional description to detour episodes"
```

---

## Task 3: Versioned CSV codec with legacy decode

Rewrite `encodeDetours`/`decodeDetours` to carry `description` and decode legacy blobs losslessly.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`

**Interfaces:**
- Consumes: `DetourEpisode(category, description)`, `sanitizeDetourCategory`, `sanitizeDetourDescription`.
- Produces: `encodeDetours(List<DetourEpisode>): String` (marked, 4-field), `decodeDetours(String): List<DetourEpisode>` (marked + legacy).

- [ ] **Step 1: Write failing codec tests**

Add to `DetoursTest.kt`:

```kotlin
// Reuses DetoursTest's existing `t(ms)` helper for instants.
private fun ep(startMs: Long, endMs: Long, category: String, description: String = "") =
    DetourEpisode(t(startMs), t(endMs), category, description)

@Test
fun encodeDecodeRoundTripsDescription() {
    val episodes = listOf(
        ep(1_000, 2_000, "Slack", "reading threads, then more"), // description keeps commas
        ep(3_000, 4_000, "Pause", ""),
    )
    assertEquals(episodes, decodeDetours(encodeDetours(episodes)))
}

@Test
fun decodeStripsCommaFromCategoryOnEncode() {
    val decoded = decodeDetours(encodeDetours(listOf(ep(1_000, 2_000, "a,b", "d"))))
    assertEquals("a b", decoded.single().category) // comma → space
    assertEquals("d", decoded.single().description)
}

@Test
fun decodeLegacyBlobMapsMotifToCategory() {
    // Legacy 3-field lines (no version marker); motif becomes category, description empty.
    val legacy = "1000,2000,café\n3000,4000,appel,imprévu"
    val decoded = decodeDetours(legacy)
    assertEquals(2, decoded.size)
    assertEquals("café", decoded[0].category)
    assertEquals("", decoded[0].description)
    assertEquals("appel imprévu", decoded[1].category) // legacy comma folded to a space
    assertEquals("", decoded[1].description)
}

@Test
fun decodeSkipsMalformedMarkedLines() {
    val blob = "@2\n1000,2000,ok,note\nblank\n5000,4000,inverted,x\n7000,8000,,nocat"
    val decoded = decodeDetours(blob)
    assertEquals(1, decoded.size)
    assertEquals("ok", decoded.single().category)
    assertEquals("note", decoded.single().description)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the versioned codec**

```kotlin
private const val DETOURS_FORMAT_MARKER = "@2"

/** Serialize episodes behind a version marker: `start,end,category,description` per line. */
fun encodeDetours(episodes: List<DetourEpisode>): String {
    val lines = episodes.joinToString("\n") {
        val category = sanitizeDetourCategory(it.category)
        val description = sanitizeDetourDescription(it.description)
        "${it.start.toEpochMilliseconds()},${it.end.toEpochMilliseconds()},$category,$description"
    }
    return if (lines.isEmpty()) DETOURS_FORMAT_MARKER else "$DETOURS_FORMAT_MARKER\n$lines"
}

/**
 * Inverse of [encodeDetours]. Marked blobs carry four fields (category 3rd, description
 * last so commas in it survive). Unmarked blobs are legacy `start,end,motif`: the motif
 * (comma-tolerant, last field) becomes the category with an empty description.
 */
fun decodeDetours(encoded: String): List<DetourEpisode> {
    if (encoded.isBlank()) return emptyList()
    val lines = encoded.split("\n")
    val marked = lines.firstOrNull() == DETOURS_FORMAT_MARKER
    val bodyLines = if (marked) lines.drop(1) else lines
    return bodyLines.mapNotNull { line ->
        val limit = if (marked) 4 else 3
        val parts = line.split(",", limit = limit)
        val start = parts.getOrNull(0)?.toLongOrNull()
        val end = parts.getOrNull(1)?.toLongOrNull()
        val category = parts.getOrNull(2)?.let(::sanitizeDetourCategory)
        val description = if (marked) parts.getOrNull(3)?.let(::sanitizeDetourDescription).orEmpty() else ""
        if (start != null && end != null && end > start && !category.isNullOrEmpty()) {
            DetourEpisode(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end), category, description)
        } else {
            null
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: PASS. (Note: the round-trip test relies on `sanitizeDetourCategory` being idempotent — encoding already-clean categories doesn't alter them.)

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
git add -A
git commit -m "Version the detour codec and carry description"
```

---

## Task 4: Thread description through the controller

`addDetour` and `completePlannedObligation` accept a description; existing episode mutators already carry it via the field (sanitized on copy).

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `DetourEpisode(category, description)`, `sanitizeDetourCategory`, `sanitizeDetourDescription`, `detourEpisodeAt(..., category, description, ...)`.
- Produces:
  - `addDetour(category: String, durationMinutes: Int, description: String = "")`
  - `completePlannedObligation(originalObligation: String, detourCategory: String, description: String, durationMinutes: Int, startMinutesOfDay: Int?)`
  - `addDetourEpisode` / `updateDetour` sanitize `description` on the copy.

- [ ] **Step 1: Write failing controller tests**

Add to `DayViewControllerTest.kt` (mirror the existing controller-test setup for constructing a controller and reading `state.detoursToday`):

```kotlin
@Test
fun addDetourStoresSanitizedDescription() {
    val controller = newControllerAtMidWindow() // existing helper pattern in this test file
    controller.addDetour("Slack", 15, "reading, threads\nmore")
    val episode = controller.state.detoursToday.single()
    assertEquals("Slack", episode.category)
    assertEquals("reading, threads more", episode.description) // newline stripped, comma kept
}

@Test
fun updateDetourKeepsDescription() {
    val controller = newControllerAtMidWindow()
    controller.addDetour("Slack", 15, "note")
    val original = controller.state.detoursToday.single()
    controller.updateDetour(0, original.copy(description = "edited note"))
    assertEquals("edited note", controller.state.detoursToday.single().description)
}
```

(If `newControllerAtMidWindow` does not exist, use the same construction the surrounding tests already use to build a `DayViewController` and advance to mid-window.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL (arity of `addDetour`).

- [ ] **Step 3: Implement controller threading**

```kotlin
fun addDetour(category: String, durationMinutes: Int, description: String = "") {
    val cleanCategory = sanitizeDetourCategory(category)
    if (cleanCategory.isEmpty()) return
    val end = state.now
    val start = maxOf(end - durationMinutes.coerceIn(1, 12 * 60).minutes, startOfLocalDay(end))
    commitDetours(
        state.detoursToday + DetourEpisode(start, end, cleanCategory, sanitizeDetourDescription(description)),
        pushCategory = cleanCategory,
    )
}
```

Update `addDetourEpisode` / `updateDetour` copies to also sanitize description:

```kotlin
val clean = episode.copy(
    category = sanitizeDetourCategory(episode.category),
    description = sanitizeDetourDescription(episode.description),
)
```

Update `completePlannedObligation` (new `description` param, third position):

```kotlin
fun completePlannedObligation(
    originalObligation: String,
    detourCategory: String,
    description: String,
    durationMinutes: Int,
    startMinutesOfDay: Int?,
) {
    if (startMinutesOfDay == null) {
        addDetour(detourCategory, durationMinutes, description)
    } else {
        addDetourEpisode(detourEpisodeAt(state.now, startMinutesOfDay, durationMinutes, detourCategory, description))
    }
    removePlannedObligation(originalObligation)
}
```

Its single call site is the obligation-completion capture in `DayViewTodayScreen.kt`. That dialog does not capture a description until Task 5, so update the call to pass `""` for now (the `onConfirm` lambda there is still the 3-arg `(confirmedCategory, durationMinutes, startMinutesOfDay)` shape from Task 1):

```kotlin
onConfirm = { confirmedCategory, durationMinutes, startMinutesOfDay ->
    actions.completePlannedObligation(motif, confirmedCategory, "", durationMinutes, startMinutesOfDay)
    obligationToComplete = null
},
```

(`App.kt`'s `addDetour` action lambda stays 2-arg and still compiles because `addDetour`'s `description` is defaulted; it becomes 3-arg in Task 5.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
git add -A
git commit -m "Thread detour description through the controller"
```

---

## Task 5: Description input in capture + edit forms

Add the optional description field to both forms, thread it through callbacks, wire `App.kt`/`DayViewTodayScreen.kt`, and add i18n strings.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourCaptureTest.kt`

**Interfaces:**
- Consumes: controller `addDetour(category, durationMinutes, description)`, `completePlannedObligation(..., description, ...)`, `detourEpisodeAt(..., category, description, ...)`.
- Produces:
  - `DetourCaptureContent(recentCategories, now, onConfirm: (category, description, durationMinutes, startMinutesOfDay) -> Unit, onForget, onDismiss, initialCategory = "", initialDescription = "")`
  - `DetourCaptureDialog(...)` same new `onConfirm` shape + `initialDescription`
  - `DayViewTestTags.DetourDescriptionField = "detourDescriptionField"`

- [ ] **Step 1: Add test tag + strings**

`DayViewTestTags.kt`:
```kotlin
const val DetourDescriptionField = "detourDescriptionField"
```

`values/strings.xml`:
```xml
<string name="detour_description_label">Description</string>
<string name="detour_description_placeholder">Optional detail</string>
```

`values-fr/strings.xml`:
```xml
<string name="detour_description_label">Description</string>
<string name="detour_description_placeholder">Détail (optionnel)</string>
```

- [ ] **Step 2: Write failing capture test**

Rewrite the three `DetourCaptureTest` cases so `onConfirm` captures 4 values, and add a description case. Example for the first case (apply the arity change to all three existing cases):

```kotlin
@Test
fun confirmsWithNullStartWhenNotAdjusted() = runComposeUiTest {
    var category: String? = null
    var description: String? = null
    var duration: Int? = null
    var start: Int? = null
    setContent {
        DetourCaptureContent(
            recentCategories = emptyList(),
            now = midWindowNow(),
            onConfirm = { c, d, dur, s -> category = c; description = d; duration = dur; start = s },
            onForget = {},
            onDismiss = {},
        )
    }
    onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("café")
    onNodeWithTag(DayViewTestTags.DetourDescriptionField).performTextInput("pause clope")
    onNodeWithTag(DayViewTestTags.DetourConfirm).performClick()

    assertEquals("café", category)
    assertEquals("pause clope", description)
    assertEquals(15, duration)
    assertNull(start)
}
```

For the other two existing cases, keep their assertions but adapt the lambda to the 4-arg shape and leave the description field untouched (expect `""`).

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetourCaptureTest"`
Expected: FAIL (unresolved `recentCategories`/`DetourDescriptionField`, arity mismatch).

- [ ] **Step 4: Implement the capture form**

In `DetourCaptureContent` (and the `DetourCaptureDialog` wrapper), change the signature:

```kotlin
internal fun DetourCaptureContent(
    recentCategories: List<String>,
    now: Instant,
    onConfirm: (category: String, description: String, durationMinutes: Int, startMinutesOfDay: Int?) -> Unit,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit,
    initialCategory: String = "",
    initialDescription: String = "",
) {
```

Add description state next to `category`:
```kotlin
var category by remember { mutableStateOf(initialCategory) }
var description by remember { mutableStateOf(initialDescription) }
```

After the recent-category chips block (before the `DURATION` section `Spacer(Modifier.height(14.dp))`), insert:
```kotlin
Spacer(Modifier.height(10.dp))
GoalTextField(
    value = description,
    semanticLabel = stringResource(Res.string.detour_description_label),
    placeholder = stringResource(Res.string.detour_description_placeholder),
    onValueChange = { description = it },
    modifier = Modifier.testTag(DayViewTestTags.DetourDescriptionField),
)
```

Update the confirm button `onClick`:
```kotlin
onClick = { onConfirm(category, description, durationMinutes, if (startPinned) startMinutes else null) },
```

Add the `import fr.dayview.app.generated.resources.detour_description_label` / `_placeholder` imports.

- [ ] **Step 5: Wire the two capture-dialog sites + App**

In `DayViewTodayScreen.kt`, the plain capture:
```kotlin
DetourCaptureDialog(
    recentCategories = state.recentDetourCategories,
    now = state.now,
    onConfirm = { category, description, durationMinutes, startMinutesOfDay ->
        if (startMinutesOfDay == null) {
            actions.addDetour(category, durationMinutes, description)
        } else {
            actions.addDetourEpisode(
                detourEpisodeAt(state.now, startMinutesOfDay, durationMinutes, category, description),
            )
        }
        showDetourCapture = false
    },
    onForget = actions.forgetDetourCategory,
    onDismiss = { showDetourCapture = false },
)
```

The obligation-completion capture:
```kotlin
DetourCaptureDialog(
    recentCategories = state.recentDetourCategories,
    now = state.now,
    initialCategory = motif,
    onConfirm = { confirmedCategory, description, durationMinutes, startMinutesOfDay ->
        actions.completePlannedObligation(motif, confirmedCategory, description, durationMinutes, startMinutesOfDay)
        obligationToComplete = null
    },
    onForget = actions.forgetDetourCategory,
    onDismiss = { obligationToComplete = null },
)
```

Update the `actions` holder `DayViewScreenActions.addDetour` field type from `(String, Int) -> Unit` to `(String, Int, String) -> Unit` (declared in `DayViewTodayScreen.kt:189`). In `App.kt`:
```kotlin
addDetour = { category, durationMinutes, description -> controller.addDetour(category, durationMinutes, description) },
```
Also update the two test-support builders in `UiTestSupport.kt` that construct `DayViewScreenActions` — `noopDayViewActions` (its `addDetour = { _, _ -> }` becomes `{ _, _, _ -> }`) and `controllerDayViewActions` (its `addDetour` lambda gains the third `description` arg, forwarded to `controller.addDetour`).
(`App.kt`'s `completePlannedObligation = controller::completePlannedObligation` is a method reference — it re-resolves to the new 5-arg signature automatically since the call in `DayViewTodayScreen.kt` now passes a description.)

- [ ] **Step 6: Add the description field to the edit form**

In `DetourEditForm`, add:
```kotlin
var description by remember { mutableStateOf(initial?.description.orEmpty()) }
```

After the existing category `GoalTextField` and its `Spacer(Modifier.height(12.dp))`, insert a description `GoalTextField` (same shape as capture, with `DayViewTestTags.DetourDescriptionField`), then a spacer. Update the SAVE `onClick`:
```kotlin
onClick = { onSave(detourEpisodeAt(now, startMinutes, durationMinutes, category, description)) },
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetourCaptureTest"`
Expected: PASS.

- [ ] **Step 8: Full check + commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
git add -A
git commit -m "Capture and edit a detour description"
```

---

## Task 6: Show the description in the list and ring hover

Render the description where detours are read: list rows and the ring-hover overlay.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourCaptureTest.kt` (or a dedicated `DetourListTest.kt` if the list is tested there)

**Interfaces:**
- Consumes: `DetourEpisode.description`, `DetourBody.description`.
- Produces: no new API; a visible description line.

- [ ] **Step 1: Write a failing list-row display test**

Add a test that drives `DetourListDialog`'s content (mirror how existing tests render list content; if `DetourListDialog` requires a `Dialog` window, extract the row column as done for `DetourCaptureContent`, or seed via `HistoryDayScreenTest`). Minimal tag-based approach — tag the description text and assert it exists:

```kotlin
@Test
fun listRowShowsDescriptionWhenPresent() = runComposeUiTest {
    val now = midWindowNow() // desktopTest helper: 13:00 local
    val dayStart = startOfLocalDay(now)
    setContent {
        DetourListContent( // extracted from DetourListDialog (Step 3)
            episodes = listOf(
                detourEpisodeAt(now, 12 * 60, 15, "Slack", "reading threads"),
            ),
            now = now,
            windowStart = dayStart,
            windowEnd = dayStart + (23 * 60 + 59).minutes,
            onUpdate = { _, _ -> },
            onRemove = {},
            onAdd = {},
            onDismiss = {},
        )
    }
    onNodeWithTag(DayViewTestTags.DetourDescriptionText, useUnmergedTree = true).assertExists()
}
```

Add `const val DetourDescriptionText = "detourDescriptionText"` to `DayViewTestTags.kt`. Imports for the test: `startOfLocalDay` is in `fr.dayview.app` (same package, no import needed); `kotlin.time.Duration.Companion.minutes` for the `.minutes` extension. `assertExists()` is a member of the node returned by `onNodeWithTag` (no separate import).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.*"`
Expected: FAIL (no description node / unresolved `DetourListContent`).

- [ ] **Step 3: Render description in list rows**

In `DetourListDialog`'s row `Column(Modifier.weight(1f))`, after the time-range `Text` and the off-window tag block, add:
```kotlin
if (episode.description.isNotEmpty()) {
    Text(
        episode.description,
        color = colors.muted,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(DayViewTestTags.DetourDescriptionText),
    )
}
```

If Step 1 needed `DetourListContent`, extract the current `Dialog { Column { ... } }` body of `DetourListDialog` into an internal `DetourListContent(...)` composable with the same parameters minus the `Dialog` wrapper, and have `DetourListDialog` call it inside `Dialog`. (Same split already used for `DetourCaptureContent`.)

- [ ] **Step 4: Render description in the ring hover overlay**

In `DayViewTodayScreen.kt`, in the `hoveredDetour?.let { ... }` block's inner `Column`, after the category `Text(body.category, ...)` and before the time-range `Text`, add:
```kotlin
if (body.description.isNotEmpty()) {
    Text(body.description, color = colors.muted, fontSize = 11.sp)
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.*"`
Expected: PASS.

- [ ] **Step 6: Full check + commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
git add -A
git commit -m "Show detour description in the list and hover"
```

---

## Final verification

- [ ] Run the full gate once more: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — green, no stderr.
- [ ] Launch the desktop app (`./gradlew :composeApp:run`), declare a detour with a category + description, reopen the list and confirm both show; hover the ring body and confirm the description appears.
- [ ] Confirm an existing history day (pre-change data, if any) still renders its detours with correct grouping/colors.
