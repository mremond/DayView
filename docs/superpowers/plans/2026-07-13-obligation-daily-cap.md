# Daily Obligation Cap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the user adding more obligations once the day's three slots are spent, counting completed (DONE) obligations against the cap while ✕-deletes free a slot.

**Architecture:** Track a second day-scoped list of completed obligation motifs alongside the active list, under the existing `plannedObligationsDayKey`. The add-cap counts `active + completed`. Completing moves a motif from the active list to the completed list; ✕ only removes from active. The completed list rides the existing `DayScoped<String>` persistence and sync machinery, so it resets at day rollover and merges across Mac/Android with no new logic.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-serialization, AndroidX DataStore, kotlin.test.

## Global Constraints

- ktlint is enforced; run `./gradlew ktlintCheck` (or `ktlintFormat`) before every commit.
- Full gate before completion: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- `MAX_PLANNED_OBLIGATIONS = 3` — do not change or make configurable.
- Sync `SYNC_SCHEMA_VERSION` stays `1`; the new field is default-empty and `SyncJson` uses `ignoreUnknownKeys = true`, so old⇄new documents stay compatible. Do NOT bump the version.
- Commit messages: English, describe the change only, no test/verification section, no AI/Claude references.
- All UI strings go through Compose resources in both `values/strings.xml` and `values-fr/strings.xml`.

---

### Task 1: Pure obligation logic — cap counts completed, and a move helper

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/PlannedObligationsTest.kt`

**Interfaces:**
- Produces:
  - `fun addPlannedObligation(current: List<String>, motif: String, alreadyUsed: Int = 0): List<String>` — cap check is now `current.size + alreadyUsed >= MAX_PLANNED_OBLIGATIONS`. The default `0` preserves every existing caller.
  - `fun markObligationCompleted(active: List<String>, completed: List<String>, motif: String): Pair<List<String>, List<String>>` — removes case-insensitive matches of `motif` from `active` and appends the sanitized `motif` to `completed`; no-op (returns inputs unchanged) if `motif` is blank or not present in `active`.

- [ ] **Step 1: Write the failing tests**

Add to `PlannedObligationsTest.kt`:

```kotlin
    @Test
    fun addCountsAlreadyUsedSlotsTowardTheCap() {
        // 1 active + 2 already-used (completed) = 3 → at cap, add is a no-op
        assertEquals(listOf("a"), addPlannedObligation(listOf("a"), "b", alreadyUsed = 2))
        // 1 active + 1 already-used = 2 → one slot free, add succeeds
        assertEquals(listOf("a", "b"), addPlannedObligation(listOf("a"), "b", alreadyUsed = 1))
    }

    @Test
    fun addDefaultsToNoAlreadyUsedSlots() {
        assertEquals(listOf("a", "b"), addPlannedObligation(listOf("a"), "b"))
    }

    @Test
    fun markCompletedMovesMotifAndSanitizes() {
        val (active, completed) = markObligationCompleted(listOf("Appel", "Facture"), emptyList(), "  appel ")
        assertEquals(listOf("Facture"), active)
        assertEquals(listOf("Appel"), completed)
    }

    @Test
    fun markCompletedIsANoOpForBlankOrMissingMotif() {
        assertEquals(listOf("a") to emptyList<String>(), markObligationCompleted(listOf("a"), emptyList(), "  \n"))
        assertEquals(listOf("a") to listOf("x"), markObligationCompleted(listOf("a"), listOf("x"), "absent"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.PlannedObligationsTest"`
Expected: FAIL — `addPlannedObligation` has no `alreadyUsed` parameter; `markObligationCompleted` is unresolved.

- [ ] **Step 3: Implement the changes**

In `PlannedObligations.kt`, replace `addPlannedObligation` and add the helper:

```kotlin
/** Append a sanitized motif; blank motifs and adds past the cap (active + [alreadyUsed]) are ignored. */
fun addPlannedObligation(current: List<String>, motif: String, alreadyUsed: Int = 0): List<String> {
    val clean = sanitizeLabel(motif, 60)
    if (clean.isEmpty() || current.size + alreadyUsed >= MAX_PLANNED_OBLIGATIONS) return current
    return current + clean
}

/**
 * Move [motif] from [active] to [completed]: drops case-insensitive matches from [active] and
 * appends the sanitized motif to [completed]. A blank motif, or one absent from [active], is a
 * no-op so the completed tally is never inflated by a phantom completion.
 */
fun markObligationCompleted(
    active: List<String>,
    completed: List<String>,
    motif: String,
): Pair<List<String>, List<String>> {
    val clean = sanitizeLabel(motif, 60)
    if (clean.isEmpty()) return active to completed
    val remaining = active.filter { it.lowercase() != clean.lowercase() }
    if (remaining.size == active.size) return active to completed
    return remaining to (completed + clean)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.PlannedObligationsTest"`
Expected: PASS (all tests, including the pre-existing ones).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/PlannedObligationsTest.kt
git commit -m "Count completed obligations toward the daily add cap"
```

---

### Task 2: Data model + controller — carry and update the completed list

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt` (add snapshot field)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (state field, accessors, coercion/mapping, controller methods)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `addPlannedObligation(current, motif, alreadyUsed)` and `markObligationCompleted(active, completed, motif)` from Task 1.
- Produces:
  - `DayPreferencesSnapshot.plannedObligationsCompleted: List<String>` (default `emptyList()`).
  - `DayViewUiState.plannedObligationsCompleted: List<String>` (default `emptyList()`).
  - `DayViewUiState.plannedObligationsCompletedToday: List<String>` — day-gated like `plannedObligationsToday`.
  - `DayViewUiState.plannedObligationSlotsUsed: Int` = `plannedObligationsToday.size + plannedObligationsCompletedToday.size`.

- [ ] **Step 1: Write the failing tests**

First, find the existing obligation test in `DayViewControllerTest.kt` to copy its controller-construction pattern:

Run: `grep -n "PlannedObligation\|fun controller\|DayViewController(" composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

Add these tests (use the same controller factory / helper the file already uses for other obligation tests; construct the controller the same way the neighbouring obligation test does):

```kotlin
    @Test
    fun completingAnObligationKeepsTheSlotUsedSoNoFourthCanBeAdded() {
        val controller = newController() // match the file's existing construction helper
        controller.addPlannedObligation("a")
        controller.addPlannedObligation("b")
        controller.addPlannedObligation("c")
        controller.completePlannedObligation("a", "cat", "a", 5, null)
        assertEquals(listOf("b", "c"), controller.state.plannedObligationsToday)
        assertEquals(3, controller.state.plannedObligationSlotsUsed)

        controller.addPlannedObligation("d")
        assertEquals(listOf("b", "c"), controller.state.plannedObligationsToday) // add refused: 2 active + 1 done = 3
    }

    @Test
    fun deletingAnObligationFreesASlotButCompletingDoesNot() {
        val controller = newController()
        controller.addPlannedObligation("a")
        controller.addPlannedObligation("b")
        controller.completePlannedObligation("a", "cat", "a", 5, null) // active {b}, completed {a} → 2 used
        controller.removePlannedObligation("b") // active {} , completed {a} → 1 used
        assertEquals(1, controller.state.plannedObligationSlotsUsed)
        controller.addPlannedObligation("c")
        assertEquals(listOf("c"), controller.state.plannedObligationsToday)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `plannedObligationSlotsUsed` is unresolved.

- [ ] **Step 3: Add the snapshot field**

In `DayPreferences.kt`, add to `DayPreferencesSnapshot` right after `plannedObligations`:

```kotlin
    val plannedObligations: List<String> = emptyList(),
    val plannedObligationsCompleted: List<String> = emptyList(),
```

- [ ] **Step 4: Add the state field and derived accessors**

In `DayViewController.kt`, in `DayViewUiState`, add the field after `plannedObligations`:

```kotlin
    val plannedObligations: List<String> = emptyList(),
    val plannedObligationsCompleted: List<String> = emptyList(),
```

And add accessors next to `plannedObligationsToday`:

```kotlin
    /** Motifs completed today; stale storage from a previous day reads as empty. */
    val plannedObligationsCompletedToday: List<String>
        get() = if (plannedObligationsDayKey == dayKeyOf(dayNow)) plannedObligationsCompleted else emptyList()

    /** Slots consumed today = still-active plus already-completed obligations. */
    val plannedObligationSlotsUsed: Int
        get() = plannedObligationsToday.size + plannedObligationsCompletedToday.size
```

- [ ] **Step 5: Thread the field through coercion and mapping**

In `DayViewController.kt`:

In `DayViewUiState.toSnapshot()` add:
```kotlin
    plannedObligations = plannedObligations,
    plannedObligationsCompleted = plannedObligationsCompleted,
```

In `DayPreferencesSnapshot.coerced()`, alongside the `plannedObligations = ...` sanitisation, add:
```kotlin
    plannedObligationsCompleted = plannedObligationsCompleted.map { sanitizeLabel(it, 60) }
        .filter { it.isNotEmpty() }
        .take(MAX_PLANNED_OBLIGATIONS),
```

In `DayPreferencesSnapshot.toUiState()` add after `plannedObligations = safe.plannedObligations,`:
```kotlin
    plannedObligationsCompleted = safe.plannedObligationsCompleted,
```

In `DayViewUiState.withPersisted()` add after `plannedObligations = safe.plannedObligations,`:
```kotlin
    plannedObligationsCompleted = safe.plannedObligationsCompleted,
```

- [ ] **Step 6: Update the controller methods**

In `DayViewController.kt`, replace the obligation methods and the private commit:

```kotlin
    fun addPlannedObligation(motif: String) {
        commitPlannedObligations(
            addPlannedObligation(state.plannedObligationsToday, motif, state.plannedObligationsCompletedToday.size),
            state.plannedObligationsCompletedToday,
        )
    }

    fun removePlannedObligation(motif: String) {
        commitPlannedObligations(
            removePlannedObligation(state.plannedObligationsToday, motif),
            state.plannedObligationsCompletedToday,
        )
    }

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
            addDetourEpisode(
                detourEpisodeAt(state.now, startMinutesOfDay, durationMinutes, detourCategory, description),
            )
        }
        val (active, completed) = markObligationCompleted(
            state.plannedObligationsToday,
            state.plannedObligationsCompletedToday,
            originalObligation,
        )
        commitPlannedObligations(active, completed)
    }

    private fun commitPlannedObligations(active: List<String>, completed: List<String>) {
        state = state.copy(
            plannedObligationsDayKey = dayKeyOf(state.now),
            plannedObligations = active,
            plannedObligationsCompleted = completed,
        )
        persistState()
    }
```

Note: `addDetour`/`addDetourEpisode` persist independently first; reading `state.plannedObligationsToday` afterward is safe because they don't touch the obligation lists.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest" --tests "fr.dayview.app.PlannedObligationsTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Remember completed obligations per day and enforce the add cap"
```

---

### Task 3: Persistence — store the completed list

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`

**Interfaces:**
- Consumes: `DayPreferencesSnapshot.plannedObligationsCompleted` (Task 2); `encodePlannedObligations` / `decodePlannedObligations` (existing, in `PlannedObligations.kt`).

- [ ] **Step 1: Write the failing test**

First inspect the existing persistence test to match its DataStore setup:

Run: `grep -n "plannedObligations\|fun .*persist\|DayPreferencesStore(" composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`

Add a test mirroring the file's existing round-trip pattern (reuse its store/dataStore helper):

```kotlin
    @Test
    fun persistsAndReloadsCompletedObligations() = runTest {
        val store = newStore() // match the file's existing store construction
        store.persist(
            DayPreferencesSnapshot(
                plannedObligationsDayKey = 19000,
                plannedObligations = listOf("b"),
                plannedObligationsCompleted = listOf("a"),
            ),
        )
        val loaded = store.snapshots.first()
        assertEquals(listOf("a"), loaded.plannedObligationsCompleted)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: FAIL — loaded `plannedObligationsCompleted` is empty (not persisted yet).

- [ ] **Step 3: Add the key, writer, and reader**

In `DayPreferencesStore.kt`:

Add to `DayPreferenceKeys` after `PLANNED_OBLIGATIONS`:
```kotlin
    const val PLANNED_OBLIGATIONS_COMPLETED = "planned_obligations_completed"
```

Add the typed key after `plannedObligationsKey`:
```kotlin
private val plannedObligationsCompletedKey = stringPreferencesKey(DayPreferenceKeys.PLANNED_OBLIGATIONS_COMPLETED)
```

In `persist(...)`, after the `plannedObligationsKey` write:
```kotlin
            prefs[plannedObligationsCompletedKey] = encodePlannedObligations(snapshot.plannedObligationsCompleted)
```

In `Preferences.toSnapshot()`, after the `plannedObligations = ...` line:
```kotlin
        plannedObligationsCompleted = decodePlannedObligations(this[plannedObligationsCompletedKey].orEmpty()),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt
git commit -m "Persist the day's completed obligations"
```

---

### Task 4: Sync — carry the completed list across devices

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncDocument.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMapper.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMerge.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncDocumentJsonTest.kt`
- Test (if present): `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncMergeTest.kt` and/or `SyncMapperTest.kt`

**Interfaces:**
- Consumes: `DayPreferencesSnapshot.plannedObligationsCompleted` (Task 2); `DayScoped<String>`, `buildDayScoped`, `mergeDayScoped`, `SyncItem` (existing).
- Produces: `SyncDocument.plannedObligationsCompleted: DayScoped<String>`.

- [ ] **Step 1: Write the failing tests**

First check what sync tests exist and how they build documents/snapshots:

Run: `ls composeApp/src/commonTest/kotlin/fr/dayview/app/sync/ && grep -n "plannedObligations" composeApp/src/commonTest/kotlin/fr/dayview/app/sync/*.kt`

In `SyncDocumentJsonTest.kt`, update the `sampleDocument()` fixture to include the new field (place directly after the `plannedObligations = ...` line):
```kotlin
        plannedObligations = DayScoped(19000, listOf(SyncItem("call", "call", false, s))),
        plannedObligationsCompleted = DayScoped(19000, listOf(SyncItem("done", "done", false, s))),
```

Add the round-trip test and a legacy-decode test. The legacy test strips the field from the encoded JSON via a `JsonObject` (a v1 document written before this field existed), then asserts it decodes to an empty, default-day-key list. Add these imports at the top of the test file:
```kotlin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
```

Tests:
```kotlin
    @Test
    fun completedObligationsRoundTripThroughJson() {
        val doc = sampleDocument()
        assertEquals(
            doc.plannedObligationsCompleted,
            decodeSyncDocument(doc.encodeToString()).plannedObligationsCompleted,
        )
    }

    @Test
    fun decodesLegacyDocumentWithoutCompletedObligations() {
        val full = SyncJson.parseToJsonElement(sampleDocument().encodeToString()).jsonObject
        val legacy = JsonObject(full - "plannedObligationsCompleted")
        val decoded = decodeSyncDocument(SyncJson.encodeToString(JsonObject.serializer(), legacy))
        assertEquals(emptyList(), decoded.plannedObligationsCompleted.items)
        assertEquals(-1L, decoded.plannedObligationsCompleted.dayKey)
    }
```

The legacy test only passes once Step 3 gives the field a default (`ignoreUnknownKeys` handles the reverse direction). It is fine to write it now and watch it fail for the right reason in Step 2.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.sync.*"`
Expected: FAIL — `SyncDocument` has no `plannedObligationsCompleted` parameter.

- [ ] **Step 3: Add the field with a default (back-compat)**

In `SyncDocument.kt`, add to `SyncDocument` after `plannedObligations`:
```kotlin
    val plannedObligations: DayScoped<String>,
    val plannedObligationsCompleted: DayScoped<String> = DayScoped(-1L, emptyList()),
```

The default makes documents written before this field decode cleanly (with `ignoreUnknownKeys` covering the reverse direction).

- [ ] **Step 4: Build, apply, and merge the field**

In `SyncMapper.kt` `buildDocument(...)`, after the `plannedObligations = buildDayScoped(...)` block:
```kotlin
        plannedObligationsCompleted = buildDayScoped(
            dayKey = snapshot.plannedObligationsDayKey,
            values = snapshot.plannedObligationsCompleted,
            keyOf = { it },
            base = base?.plannedObligationsCompleted,
            fresh = fresh,
        ),
```

In `SyncMapper.kt` `applyDocument(...)`, after the `plannedObligations = ...` mapping:
```kotlin
        plannedObligationsCompleted = document.plannedObligationsCompleted.items.filterNot { it.deleted }.map { it.value },
```

In `SyncMerge.kt` `merge(...)`, after the `plannedObligations = mergeDayScoped(...)` line:
```kotlin
        plannedObligationsCompleted = mergeDayScoped(plannedObligationsCompleted, remote.plannedObligationsCompleted),
```

- [ ] **Step 5: Add a merge test (if a merge test file exists)**

If `SyncMergeTest.kt` exists, add a test asserting completed motifs union across devices and that completing on one device tombstones the motif in the other's active list. Match the file's existing document-building helpers:

```kotlin
    @Test
    fun mergeUnionsCompletedObligationsAcrossDevices() {
        val a = sampleDocument("dev-a", at = 100).copy(
            plannedObligationsCompleted = DayScoped(19000, listOf(SyncItem("x", "x", false, Stamp(100, "dev-a")))),
        )
        val b = sampleDocument("dev-b", at = 200).copy(
            plannedObligationsCompleted = DayScoped(19000, listOf(SyncItem("y", "y", false, Stamp(200, "dev-b")))),
        )
        val merged = a.merge(b)
        assertEquals(setOf("x", "y"), merged.plannedObligationsCompleted.items.filterNot { it.deleted }.map { it.value }.toSet())
    }
```

If no merge test file exists, skip this step (round-trip + apply coverage is sufficient); do not create a new file solely for this.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.sync.*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/ \
        composeApp/src/commonTest/kotlin/fr/dayview/app/sync/
git commit -m "Sync the day's completed obligations across devices"
```

---

### Task 5: UI — chip shows slots used, add field locks at the cap with a hint

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.plannedObligationSlotsUsed` (Task 2).
- Produces:
  - `PlannedObligationsContent(..., slotsUsed: Int = obligations.size, ...)` — new param; `atCap = slotsUsed >= MAX_PLANNED_OBLIGATIONS`. Default keeps existing tests valid.
  - `PlannedObligationsDialog(..., slotsUsed: Int, ...)` — forwards `slotsUsed` to the content.

- [ ] **Step 1: Add the strings**

In `values/strings.xml`, after `planned_obligation_remove_label`:
```xml
    <string name="planned_obligations_cap_reached">All 3 of today\'s obligations are used.</string>
```

In `values-fr/strings.xml`, after `planned_obligation_remove_label`:
```xml
    <string name="planned_obligations_cap_reached">Les 3 obligations du jour sont utilisées.</string>
```

- [ ] **Step 2: Write the failing UI tests**

In `PlannedObligationsUiTest.kt`, update the existing `addFieldHiddenAtTheCap` call to pass `slotsUsed` and add a hint test. Because these tests assert by tag (never `stringResource` text — the harness can't resolve it on CI), tag the hint. Add tag constant usage.

Update/add:
```kotlin
    @Test
    fun addFieldHiddenWhenSlotsSpentByCompletions() = runComposeUiTest {
        setContent {
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("a"),
                    slotsUsed = 3, // 1 active + 2 completed
                    onAdd = {},
                    onComplete = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationInput).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.PlannedObligationsCapHint).assertExists()
    }

    @Test
    fun addFieldVisibleBelowTheCap() = runComposeUiTest {
        setContent {
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("a"),
                    slotsUsed = 1,
                    onAdd = {},
                    onComplete = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationInput).assertExists()
        onNodeWithTag(DayViewTestTags.PlannedObligationsCapHint).assertDoesNotExist()
    }
```

Add the tag constant to `DayViewTestTags.kt` after `PlannedObligationRemove`:
```kotlin
    const val PlannedObligationsCapHint = "plannedObligationsCapHint"
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.PlannedObligationsUiTest"`
Expected: FAIL — `slotsUsed` param unresolved / `PlannedObligationsCapHint` unresolved.

- [ ] **Step 4: Update the content composable**

In `PlannedObligationsUi.kt`:

Add the import for the new string:
```kotlin
import fr.dayview.app.generated.resources.planned_obligations_cap_reached
```

Change `PlannedObligationsDialog` to accept and forward `slotsUsed`:
```kotlin
@Composable
internal fun PlannedObligationsDialog(
    obligations: List<String>,
    slotsUsed: Int,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        PlannedObligationsContent(obligations, slotsUsed, onAdd, onComplete, onRemove, onDismiss)
    }
}
```

Change `PlannedObligationsContent` signature and cap logic:
```kotlin
@Composable
internal fun PlannedObligationsContent(
    obligations: List<String>,
    slotsUsed: Int = obligations.size,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    var draft by remember { mutableStateOf("") }
    val atCap = slotsUsed >= MAX_PLANNED_OBLIGATIONS
    val removeLabel = stringResource(Res.string.planned_obligation_remove_label)
```

Replace the `if (!atCap) { ... }` block's tail so a hint shows when the cap is reached but the visible list is not itself full (i.e. completions consumed the slots):
```kotlin
        if (!atCap) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalTextField(
                    value = draft,
                    semanticLabel = stringResource(Res.string.planned_obligation_motif_label),
                    placeholder = stringResource(Res.string.planned_obligation_motif_placeholder),
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).testTag(DayViewTestTags.PlannedObligationInput),
                )
                Spacer(Modifier.width(6.dp))
                FocusActionButton(
                    label = stringResource(Res.string.planned_obligation_add_button),
                    color = colors.muted,
                    modifier = Modifier.testTag(DayViewTestTags.PlannedObligationAdd),
                    enabled = draft.isNotBlank(),
                    onClick = {
                        onAdd(draft)
                        draft = ""
                    },
                )
            }
        } else if (obligations.size < MAX_PLANNED_OBLIGATIONS) {
            Text(
                stringResource(Res.string.planned_obligations_cap_reached),
                color = colors.muted,
                fontSize = 11.sp,
                modifier = Modifier.testTag(DayViewTestTags.PlannedObligationsCapHint),
            )
        }
```

- [ ] **Step 5: Wire slots-used from the screen**

In `DayViewTodayScreen.kt`:

Both `PlannedObligationsChip(...)` call sites — change `count = state.plannedObligationsToday.size` to:
```kotlin
                    count = state.plannedObligationSlotsUsed,
```
(Search for `PlannedObligationsChip(` — there are two; update both. Leave `cap = MAX_PLANNED_OBLIGATIONS` unchanged.)

The `PlannedObligationsDialog(...)` call — add the `slotsUsed` argument after `obligations`:
```kotlin
            PlannedObligationsDialog(
                obligations = state.plannedObligationsToday,
                slotsUsed = state.plannedObligationSlotsUsed,
                onAdd = actions.addPlannedObligation,
                onComplete = {
                    obligationToComplete = it
                    showObligations = false
                },
                onRemove = actions.removePlannedObligation,
                onDismiss = { showObligations = false },
            )
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.PlannedObligationsUiTest"`
Expected: PASS (all four tests, including the two pre-existing ones that rely on the `slotsUsed` default).

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt
git commit -m "Show obligation slots used and lock adding at the daily cap"
```

---

### Task 6: Full gate

**Files:** none (verification only).

- [ ] **Step 1: Run the full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint violations, no failing tests, no stderr.

- [ ] **Step 2: If ktlint complains, auto-fix and re-run**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit any formatting fixes**

```bash
git add -A && git commit -m "Apply ktlint formatting" # only if ktlintFormat changed files
```

---

## Self-Review Notes

- **Spec coverage:** data model (Task 2), pure cap logic (Task 1), controller (Task 2), persistence (Task 3), sync build/apply/merge + no version bump (Task 4), chip = slots-used + add-lock + hint + EN/FR strings (Task 5), rollover (free via day-scoping, asserted implicitly by `plannedObligationsCompletedToday` gating and the controller tests). All spec sections map to a task.
- **Back-compat:** the sync field has a default so legacy documents decode; schema version stays 1 (Global Constraints + Task 4 Step 3).
- **Type consistency:** `alreadyUsed` (Task 1) ↔ `plannedObligationsCompletedToday.size` (Task 2); `markObligationCompleted` returns `Pair<List<String>, List<String>>` consumed by `completePlannedObligation` (Task 2); `slotsUsed` param name consistent across `PlannedObligationsDialog`/`PlannedObligationsContent`/call sites (Task 5); `PlannedObligationsCapHint` tag defined and used (Task 5).
- **Test-factory caveat:** Tasks 2–4 tell the implementer to match each test file's existing construction helpers (`newController()`/`newStore()` are placeholders for whatever those files already use) rather than invent new ones — inspect-first steps are included.
