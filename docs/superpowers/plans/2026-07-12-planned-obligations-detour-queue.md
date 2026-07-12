# Planned Obligations (Pre-Declared Detour Queue) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner pre-declare up to 3 daily obligations (motif-only), where marking one "done" logs it as an ordinary detour and drops it from the list.

**Architecture:** A pure-function model over `List<String>` (mirroring the `Detours.kt` family), a day-scoped pair of fields threaded through the preferences snapshot / store / controller (exactly like detours), and a compact home-screen section that reuses the existing detour capture dialog to log a completed obligation. Nothing new touches the ring, the countdown, net time, streak, or clean sessions.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, AndroidX DataStore preferences, Compose resources (strings), kotlinx-datetime, kotlin.test.

## Global Constraints

- Cap fixed in code, not configurable: `MAX_PLANNED_OBLIGATIONS = 3`.
- Day-scoped via the existing `dayKeyOf` convention; no carry-over across the day rollover; no debt for undone obligations.
- A completed obligation becomes an ordinary `DetourEpisode` — no new ring marking, color, or budget counter.
- Motifs are sanitized with the existing `sanitizeDetourMotif` (single-line, trimmed, ≤60 chars).
- No new settings surface (consistent with the clean-focus-sessions "no settings in v1" stance).
- Every user-facing string gets an entry in **both** `values/strings.xml` (English) and `values-fr/strings.xml` (French).
- UI tests must never assert on `stringResource` text (unresolved under `runComposeUiTest` here) — drive by test tag only.
- ktlint is enforced. Run before every commit:
  `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
- Never add any Claude/Anthropic/AI reference to commit messages.

---

### Task 1: Pure model — `PlannedObligations.kt`

A dedicated file keeps `Detours.kt` focused. Pure functions only, fully unit-testable without UI, following the `Detours.kt` idiom (`sanitizeDetourMotif`, `encode*`/`decode*`, `push*`/`remove*`).

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/PlannedObligationsTest.kt`

**Interfaces:**
- Consumes: `sanitizeDetourMotif(String): String` from `Detours.kt`.
- Produces:
  - `const val MAX_PLANNED_OBLIGATIONS = 3`
  - `fun addPlannedObligation(current: List<String>, motif: String): List<String>` — sanitizes; ignores a blank motif; ignores the add when already at the cap; otherwise appends to the end (order preserved).
  - `fun removePlannedObligation(current: List<String>, motif: String): List<String>` — removes every case-insensitive match of the sanitized motif; a blank or missing motif is a no-op.
  - `fun encodePlannedObligations(obligations: List<String>): String` — one motif per line.
  - `fun decodePlannedObligations(encoded: String): List<String>` — trims, drops blanks, sanitizes, and caps at `MAX_PLANNED_OBLIGATIONS`.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/PlannedObligationsTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PlannedObligationsTest {
    @Test
    fun addSanitizesAppendsAndPreservesOrder() {
        val one = addPlannedObligation(emptyList(), "  Appel\nclient ")
        assertEquals(listOf("Appel client"), one)
        assertEquals(listOf("Appel client", "Facture"), addPlannedObligation(one, "Facture"))
    }

    @Test
    fun addIgnoresBlankMotifs() {
        assertEquals(listOf("Facture"), addPlannedObligation(listOf("Facture"), "  \n"))
    }

    @Test
    fun addIsANoOpAtTheCap() {
        val full = listOf("a", "b", "c")
        assertEquals(MAX_PLANNED_OBLIGATIONS, full.size)
        assertEquals(full, addPlannedObligation(full, "d"))
    }

    @Test
    fun removeDropsMotifCaseInsensitivelyAndNoOpsOnBlankOrMissing() {
        assertEquals(listOf("Facture"), removePlannedObligation(listOf("Facture", "Appel"), "  appel "))
        assertEquals(listOf("Facture"), removePlannedObligation(listOf("Facture"), "absent"))
        assertEquals(listOf("Facture"), removePlannedObligation(listOf("Facture"), "  \n"))
    }

    @Test
    fun encodeDecodeRoundTripsAndCapsOnDecode() {
        val obligations = listOf("Appel client", "Facture")
        assertEquals(obligations, decodePlannedObligations(encodePlannedObligations(obligations)))
        assertEquals(emptyList(), decodePlannedObligations(""))
        assertEquals(3, decodePlannedObligations("a\nb\nc\nd\ne").size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.PlannedObligationsTest"`
Expected: FAIL — unresolved references (`addPlannedObligation`, `MAX_PLANNED_OBLIGATIONS`, …).

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt`:

```kotlin
package fr.dayview.app

/** The day's must-do obligations, capped so they never crowd out the goal. */
const val MAX_PLANNED_OBLIGATIONS = 3

/** Append a sanitized motif; blank motifs and adds past the cap are ignored. */
fun addPlannedObligation(current: List<String>, motif: String): List<String> {
    val clean = sanitizeDetourMotif(motif)
    if (clean.isEmpty() || current.size >= MAX_PLANNED_OBLIGATIONS) return current
    return current + clean
}

/** Drop every case-insensitive match of [motif]; a blank or missing motif is a no-op. */
fun removePlannedObligation(current: List<String>, motif: String): List<String> {
    val clean = sanitizeDetourMotif(motif)
    if (clean.isEmpty()) return current
    return current.filter { it.lowercase() != clean.lowercase() }
}

/** One motif per line; motifs are single-line by construction. */
fun encodePlannedObligations(obligations: List<String>): String = obligations.joinToString("\n")

/** Inverse of [encodePlannedObligations]; drops blanks, sanitizes, and caps on decode. */
fun decodePlannedObligations(encoded: String): List<String> =
    encoded.split("\n")
        .map(::sanitizeDetourMotif)
        .filter { it.isNotEmpty() }
        .take(MAX_PLANNED_OBLIGATIONS)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.PlannedObligationsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/PlannedObligationsTest.kt
git commit -m "Add planned-obligations model (motif list, capped at 3)"
```

---

### Task 2: Persistence — snapshot fields + store keys

Thread a day-scoped pair (`plannedObligationsDayKey`, `plannedObligations`) through the snapshot and the DataStore store, mirroring the detour keys one-for-one.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt` (add two snapshot fields after `recentDetourMotifs`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt` (key names, key vals, persist writes, `toSnapshot` reads)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt` (add a round-trip test)

**Interfaces:**
- Consumes: `encodePlannedObligations`, `decodePlannedObligations` (Task 1).
- Produces: `DayPreferencesSnapshot.plannedObligationsDayKey: Long` (default `-1L`) and `DayPreferencesSnapshot.plannedObligations: List<String>` (default `emptyList()`).

- [ ] **Step 1: Write the failing test**

In `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`, add:

```kotlin
    @Test
    fun plannedObligationsRoundTrip() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(
            plannedObligationsDayKey = 20_646L,
            plannedObligations = listOf("Appel client", "Facture"),
        )
        store.persist(snapshot)
        val read = store.snapshots.first()
        assertEquals(20_646L, read.plannedObligationsDayKey)
        assertEquals(listOf("Appel client", "Facture"), read.plannedObligations)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayPreferencesStoreTest.plannedObligationsRoundTrip"`
Expected: FAIL — `plannedObligationsDayKey` / `plannedObligations` unresolved on `DayPreferencesSnapshot`.

- [ ] **Step 3a: Add the snapshot fields**

In `DayPreferences.kt`, in `DayPreferencesSnapshot`, immediately after the `recentDetourMotifs` line:

```kotlin
    val recentDetourMotifs: List<String> = emptyList(),
    val plannedObligationsDayKey: Long = -1L,
    val plannedObligations: List<String> = emptyList(),
```

- [ ] **Step 3b: Add the store key names**

In `DayPreferencesStore.kt`, inside `object DayPreferenceKeys`, after `DETOUR_RECENT_MOTIFS`:

```kotlin
        const val DETOUR_RECENT_MOTIFS = "detour_recent_motifs"
        const val PLANNED_OBLIGATIONS_DAY = "planned_obligations_day"
        const val PLANNED_OBLIGATIONS = "planned_obligations"
```

- [ ] **Step 3c: Add the typed key vals**

After the `detourRecentMotifsKey` val:

```kotlin
private val detourRecentMotifsKey = stringPreferencesKey(DayPreferenceKeys.DETOUR_RECENT_MOTIFS)
private val plannedObligationsDayPrefKey = longPreferencesKey(DayPreferenceKeys.PLANNED_OBLIGATIONS_DAY)
private val plannedObligationsKey = stringPreferencesKey(DayPreferenceKeys.PLANNED_OBLIGATIONS)
```

- [ ] **Step 3d: Write the values in `persist`**

In `persist`, after the `prefs[detourRecentMotifsKey] = ...` line:

```kotlin
            prefs[detourRecentMotifsKey] = encodeRecentDetourMotifs(snapshot.recentDetourMotifs)
            prefs[plannedObligationsDayPrefKey] = snapshot.plannedObligationsDayKey
            prefs[plannedObligationsKey] = encodePlannedObligations(snapshot.plannedObligations)
```

- [ ] **Step 3e: Read the values in `toSnapshot`**

After the `recentDetourMotifs = decodeRecentDetourMotifs(...)` line:

```kotlin
        recentDetourMotifs = decodeRecentDetourMotifs(this[detourRecentMotifsKey].orEmpty()),
        plannedObligationsDayKey = this[plannedObligationsDayPrefKey] ?: defaults.plannedObligationsDayKey,
        plannedObligations = decodePlannedObligations(this[plannedObligationsKey].orEmpty()),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: PASS (all tests in the class, including the existing `missingValuesFallBackToDefaults` which now also exercises the new defaults).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt
git commit -m "Persist planned obligations in day preferences"
```

---

### Task 3: Controller wiring — state, day-scope, add/remove/complete

Expose the day-scoped list on the UI state and provide the three mutating entry points, following the `commitDetours` pattern exactly (set day key + list, then `persistState()`).

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: top-level `addPlannedObligation`, `removePlannedObligation`, `MAX_PLANNED_OBLIGATIONS` (Task 1); `dayKeyOf` (Detours.kt); the snapshot fields (Task 2); the existing `addDetour(motif, durationMinutes)`.
- Produces:
  - `DayViewUiState.plannedObligationsDayKey: Long`, `DayViewUiState.plannedObligations: List<String>`
  - `DayViewUiState.plannedObligationsToday: List<String>` (empty when the day key is stale)
  - `fun DayViewController.addPlannedObligation(motif: String)`
  - `fun DayViewController.removePlannedObligation(motif: String)`
  - `fun DayViewController.completePlannedObligation(motif: String, durationMinutes: Int)` — logs the detour then drops the obligation.

Note on naming: the controller methods intentionally share names with the Task 1 top-level functions. Overload resolution disambiguates by arity — a 2-argument call (`addPlannedObligation(list, motif)`) binds to the top-level pure function, a 1-argument call (`removePlannedObligation(motif)`) binds to the controller member. The code blocks below rely on this; type them verbatim.

- [ ] **Step 1: Write the failing tests**

In `DayViewControllerTest.kt`, add:

```kotlin
    @Test
    fun addPlannedObligationStoresItDayScopedAndCapsAtThree() {
        val preferences = InMemoryDayPreferences()
        val now = 1_800_000_000_000L
        val controller = testController(preferences, now)

        controller.addPlannedObligation(" Appel\nclient ")
        controller.addPlannedObligation("Facture")
        controller.addPlannedObligation("Courses")
        controller.addPlannedObligation("Trop") // over the cap, ignored

        val stored = preferences.current
        assertEquals(dayKeyOf(t(now)), stored.plannedObligationsDayKey)
        assertEquals(listOf("Appel client", "Facture", "Courses"), stored.plannedObligations)
    }

    @Test
    fun addPlannedObligationOnANewDayReplacesTheStaleList() {
        val now = 1_800_000_000_000L
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                plannedObligationsDayKey = dayKeyOf(t(now)) - 1L,
                plannedObligations = listOf("Vieux"),
            ),
        )
        val controller = testController(preferences, now)

        controller.addPlannedObligation("Neuf")

        assertEquals(listOf("Neuf"), preferences.current.plannedObligations)
    }

    @Test
    fun completePlannedObligationLogsADetourAndRemovesTheObligation() {
        val preferences = InMemoryDayPreferences()
        val now = 1_800_000_000_000L
        val controller = testController(preferences, now)
        controller.addPlannedObligation("Appel client")

        controller.completePlannedObligation("Appel client", 30)

        val stored = preferences.current
        assertEquals(emptyList(), stored.plannedObligations)
        val episode = stored.detours.single()
        assertEquals("Appel client", episode.motif)
        assertEquals(30, episode.duration.inWholeMinutes)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest.addPlannedObligation*" --tests "fr.dayview.app.DayViewControllerTest.completePlannedObligation*"`
Expected: FAIL — unresolved `addPlannedObligation` / `completePlannedObligation` / `plannedObligations` on the controller/state.

- [ ] **Step 3a: Add the state fields**

In `DayViewController.kt`, in `internal data class DayViewUiState(`, after the `recentDetourMotifs` field (near the existing `detoursDayKey` / `detours` fields):

```kotlin
    val recentDetourMotifs: List<String> = emptyList(),
    val plannedObligationsDayKey: Long = -1L,
    val plannedObligations: List<String> = emptyList(),
```

- [ ] **Step 3b: Add the day-scoped derived accessor**

Next to `detoursToday` (the `if (detoursDayKey == dayKeyOf(dayNow)) detours else emptyList()` accessor):

```kotlin
    val plannedObligationsToday: List<String>
        get() = if (plannedObligationsDayKey == dayKeyOf(dayNow)) plannedObligations else emptyList()
```

- [ ] **Step 3c: Add the mutating methods + commit helper**

Next to `commitDetours` / `addDetour`:

```kotlin
    fun addPlannedObligation(motif: String) {
        commitPlannedObligations(addPlannedObligation(state.plannedObligationsToday, motif))
    }

    fun removePlannedObligation(motif: String) {
        commitPlannedObligations(removePlannedObligation(state.plannedObligationsToday, motif))
    }

    fun completePlannedObligation(motif: String, durationMinutes: Int) {
        addDetour(motif, durationMinutes)
        removePlannedObligation(motif)
    }

    private fun commitPlannedObligations(obligations: List<String>) {
        state = state.copy(
            plannedObligationsDayKey = dayKeyOf(state.now),
            plannedObligations = obligations,
        )
        persistState()
    }
```

- [ ] **Step 3d: Thread the fields through the snapshot mappers**

Mirror the three detour lines wherever they appear. At the snapshot-building sites (search for `detoursDayKey = ` — mappings near the `toSnapshot`/`fromSnapshot`/sanitize helpers, e.g. around lines 415, 461, 485), add alongside each detour trio:

```kotlin
        plannedObligationsDayKey = plannedObligationsDayKey,
        plannedObligations = plannedObligations,
```

And in the sanitizing pass (where `detours = detours.map { it.copy(motif = sanitizeDetourMotif(it.motif)) }` and `recentDetourMotifs = recentDetourMotifs.take(...)` appear, ~line 433):

```kotlin
        plannedObligations = plannedObligations.map(::sanitizeDetourMotif)
            .filter { it.isNotEmpty() }
            .take(MAX_PLANNED_OBLIGATIONS),
```

Use the same source-field name on the right-hand side as the surrounding detour lines use (they read straight off the snapshot/state being mapped). If the compiler reports a missing argument for a `copy`/constructor call, that call is one of these mapping sites — add the pair there too.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS (the whole class, to confirm no mapping site was missed).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Wire planned obligations through the controller"
```

---

### Task 4: UI — obligations section + reuse of the detour capture dialog

A compact section on the single-page home lists the ≤3 motifs (each with a "done" button) and, below the cap, a motif field plus an "add" button. At the cap the add row disappears entirely. "Done" opens the existing `DetourCaptureDialog` with the motif pre-filled; confirming logs the detour and drops the obligation via `completePlannedObligation`.

The section reuses the codebase's own widgets — `GoalTextField` and `FocusActionButton` (both `internal` in `DayViewTodayScreen.kt`, same `fr.dayview.app` package, so no import needed) — and the `LocalDayViewColors` palette (which has a `DarkDayViewColors` default, so no theme provider is needed in tests). No Material icon dependency is introduced.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` (add tags)
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` and `.../values-fr/strings.xml` (new strings)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt` (add an `initialMotif` param to the capture dialog/content so it can be pre-filled)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (new actions on `DayViewScreenActions`, render the section, drive the pre-filled capture)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt`

**Interfaces:**
- Consumes: `state.plannedObligationsToday` and the actions `addPlannedObligation(motif)`, `completePlannedObligation(motif, durationMinutes)` (Task 3); `MAX_PLANNED_OBLIGATIONS` (Task 1); `GoalTextField`, `FocusActionButton`, `LocalDayViewColors` (existing, same package); the existing `DetourCaptureDialog`.
- Produces: `@Composable internal fun PlannedObligationsSection(obligations: List<String>, onAdd: (String) -> Unit, onComplete: (String) -> Unit, modifier: Modifier = Modifier)`; test tags `PlannedObligationInput`, `PlannedObligationAdd`, `PlannedObligationDone`.

- [ ] **Step 1: Add the test tags**

In `DayViewTestTags.kt`, after the detour tags:

```kotlin
    const val DetourConfirm = "detourConfirm"
    const val PlannedObligationInput = "plannedObligationInput"
    const val PlannedObligationAdd = "plannedObligationAdd"
    const val PlannedObligationDone = "plannedObligationDone"
```

- [ ] **Step 2: Add the string resources**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, near the `detour_*` entries:

```xml
    <string name="planned_obligations_title">Today's obligations</string>
    <string name="planned_obligation_motif_label">Obligation to do today</string>
    <string name="planned_obligation_motif_placeholder">e.g. Client call</string>
    <string name="planned_obligation_add_button">ADD</string>
    <string name="planned_obligation_done_button">DONE</string>
```

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, the same keys with French copy:

```xml
    <string name="planned_obligations_title">Obligations du jour</string>
    <string name="planned_obligation_motif_label">Obligation à faire aujourd'hui</string>
    <string name="planned_obligation_motif_placeholder">ex. Appel client</string>
    <string name="planned_obligation_add_button">AJOUTER</string>
    <string name="planned_obligation_done_button">FAIT</string>
```

- [ ] **Step 3: Write the failing UI test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt`. Drive by test tag only (never assert `stringResource` text). At the cap the add field is not rendered, so assert it does not exist:

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlannedObligationsUiTest {
    @Test
    fun addFieldHiddenAtTheCap() = runComposeUiTest {
        setContent {
            PlannedObligationsSection(
                obligations = listOf("a", "b", "c"),
                onAdd = {},
                onComplete = {},
            )
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationInput).assertDoesNotExist()
    }

    @Test
    fun doneReportsTheRowMotif() = runComposeUiTest {
        var completed: String? = null
        setContent {
            PlannedObligationsSection(
                obligations = listOf("Appel client"),
                onAdd = {},
                onComplete = { completed = it },
            )
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationDone).performClick()
        assertEquals("Appel client", completed)
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.PlannedObligationsUiTest"`
Expected: FAIL — `PlannedObligationsSection` unresolved.

- [ ] **Step 5: Implement `PlannedObligationsSection`**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dayview.composeapp.generated.resources.Res
import dayview.composeapp.generated.resources.planned_obligation_add_button
import dayview.composeapp.generated.resources.planned_obligation_done_button
import dayview.composeapp.generated.resources.planned_obligation_motif_label
import dayview.composeapp.generated.resources.planned_obligation_motif_placeholder
import dayview.composeapp.generated.resources.planned_obligations_title
import org.jetbrains.compose.resources.stringResource

/** The day's must-do obligations: at most [MAX_PLANNED_OBLIGATIONS], each completable. */
@Composable
internal fun PlannedObligationsSection(
    obligations: List<String>,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    var draft by remember { mutableStateOf("") }
    val atCap = obligations.size >= MAX_PLANNED_OBLIGATIONS

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.planned_obligations_title), color = colors.muted)

        obligations.forEach { motif ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(motif, color = colors.ink, modifier = Modifier.weight(1f))
                FocusActionButton(
                    label = stringResource(Res.string.planned_obligation_done_button),
                    color = colors.mint,
                    modifier = Modifier.testTag(DayViewTestTags.PlannedObligationDone),
                    onClick = { onComplete(motif) },
                )
            }
        }

        if (!atCap) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalTextField(
                    value = draft,
                    semanticLabel = stringResource(Res.string.planned_obligation_motif_label),
                    placeholder = stringResource(Res.string.planned_obligation_motif_placeholder),
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).testTag(DayViewTestTags.PlannedObligationInput),
                )
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
        }
    }
}
```

Notes for the implementer:
- The `PlannedObligationDone` tag is intentionally shared across rows; the UI test uses a single-row list so `onNodeWithTag` is unambiguous. If a later change taps a specific row among several, switch the test to `onAllNodesWithTag(...)[index]` rather than making the tag unique.
- If the generated `Res` import paths differ, copy the exact package the existing `detour_*` string imports use in `DetoursUi.kt` (same `dayview.composeapp.generated.resources` root) — only the trailing string-name identifiers change.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.PlannedObligationsUiTest"`
Expected: PASS.

- [ ] **Step 7: Add an `initialMotif` to the detour capture dialog**

In `DetoursUi.kt`, give `DetourCaptureDialog` and `DetourCaptureContent` an `initialMotif: String = ""` parameter, and seed the motif field's initial state from it — the `remember { mutableStateOf(...) }` backing the `motif` var that currently starts empty becomes `remember { mutableStateOf(initialMotif) }`. The default `""` preserves every existing call site unchanged.

- [ ] **Step 8: Wire the section into the Today screen**

In `DayViewTodayScreen.kt`:
- Add to `internal data class DayViewScreenActions(` (next to `addDetour` / `addDetourEpisode`):
  ```kotlin
      val addPlannedObligation: (String) -> Unit,
      val completePlannedObligation: (String, Int) -> Unit,
  ```
- Add capture state near `var showDetourCapture by remember { mutableStateOf(false) }`:
  ```kotlin
      var obligationToComplete by remember { mutableStateOf<String?>(null) }
  ```
- Render `PlannedObligationsSection(obligations = state.plannedObligationsToday, onAdd = actions.addPlannedObligation, onComplete = { obligationToComplete = it })` in both layout branches where `DetourRow(` is placed (there are two call sites — search for `DetourRow(`).
- Where `showDetourCapture` drives a `DetourCaptureDialog`, add a sibling: when `obligationToComplete != null`, show a `DetourCaptureDialog(recentMotifs = state.recentDetourMotifs, initialMotif = obligationToComplete!!, onCapture = { motif, durationMinutes, _ -> actions.completePlannedObligation(motif, durationMinutes); obligationToComplete = null }, onForget = actions.forgetDetourMotif, onDismiss = { obligationToComplete = null })`. Match the exact `onCapture` lambda arity the existing `DetourCaptureDialog` call uses.
- At the `DayViewScreenActions(...)` construction site (search for `addDetourEpisode =`), pass `addPlannedObligation = controller::addPlannedObligation` and `completePlannedObligation = controller::completePlannedObligation`.
- Any other `DayViewScreenActions(...)` construction (e.g. test/preview helpers under `desktopTest` or `commonMain`) must also supply the two new callbacks — the compiler will flag each missing one; pass `{}` / `{ _, _ -> }` no-ops in preview/test helpers.

- [ ] **Step 9: Run the full suite + lint**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS with no ktlint errors and no stderr.

- [ ] **Step 10: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt
git commit -m "Surface planned obligations on the home screen"
```

---

## Notes on deferred scope (do NOT build)

Pre-filled or estimated durations at declaration time · carry-over to the next day · a "planned" marking or color on the ring · a budget / within-budget counter · a configurable cap · ordering or priorities among obligations · any effect on countdown, net time, streak, or clean sessions. These are explicitly out of scope for v1 per the design doc.
