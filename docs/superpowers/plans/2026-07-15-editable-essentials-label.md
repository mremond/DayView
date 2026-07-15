# Editable Essentials Label Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user rename each active "incontournable" (planned obligation) in place, while it is not yet done.

**Architecture:** A pure `editPlannedObligation(...)` function in the `core` module validates and applies a rename (position-preserving, blank/unchanged/duplicate rejected). A thin controller method routes accepted edits through the existing `commitPlannedObligations` funnel (persistence + sync unchanged). In the UI, each active item becomes a `GoalTextField` that commits on focus loss; rejected edits revert because `key(motif)` recreates the row only on an accepted change.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlin.test, Compose UI test (`runComposeUiTest`).

## Global Constraints

- All commit messages and code comments in English; no reference to Claude/Anthropic/AI in commits.
- ktlint is enforced — run `./gradlew ktlintCheck` before committing.
- Full gate before finishing: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Identity of an obligation is its string, matched case-insensitively (`matchesPlannedObligation`). No stable IDs.
- Label length cap: 60 chars via `sanitizeLabel(raw, 60)` (single-line, trimmed).
- Editable applies to the **active** list only (`plannedObligations`); the completed list stays read-only.

---

### Task 1: Pure `editPlannedObligation` logic

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/PlannedObligationsTest.kt`

**Interfaces:**
- Consumes: `sanitizeLabel(raw: String, maxLen: Int): String` (from `Detours.kt`), `matchesPlannedObligation(entry: String, motif: String): Boolean` (same file).
- Produces: `fun editPlannedObligation(active: List<String>, completed: List<String>, oldMotif: String, newLabel: String): List<String>?` — returns the new active list, or `null` when the edit is rejected.

- [ ] **Step 1: Write the failing tests**

Add to `PlannedObligationsTest.kt` (inside the existing `class PlannedObligationsTest`):

```kotlin
@Test
fun editRenamesInPlaceAndPreservesOrder() {
    assertEquals(
        listOf("a", "B2", "c"),
        editPlannedObligation(listOf("a", "b", "c"), emptyList(), oldMotif = "b", newLabel = "B2"),
    )
}

@Test
fun editSanitizesTheNewLabel() {
    assertEquals(
        listOf("a", "New label", "c"),
        editPlannedObligation(listOf("a", "b", "c"), emptyList(), oldMotif = "b", newLabel = "  New\nlabel "),
    )
}

@Test
fun editMatchesOldMotifCaseInsensitively() {
    assertEquals(
        listOf("Facture"),
        editPlannedObligation(listOf("Appel"), emptyList(), oldMotif = "  appel ", newLabel = "Facture"),
    )
}

@Test
fun editAllowsCasingOnlyChange() {
    assertEquals(
        listOf("Appel"),
        editPlannedObligation(listOf("appel"), emptyList(), oldMotif = "appel", newLabel = "Appel"),
    )
}

@Test
fun editRejectsBlankLabel() {
    assertEquals(null, editPlannedObligation(listOf("a", "b"), emptyList(), oldMotif = "b", newLabel = "  \n"))
}

@Test
fun editRejectsUnchangedLabel() {
    assertEquals(null, editPlannedObligation(listOf("a", "b"), emptyList(), oldMotif = "b", newLabel = "b"))
}

@Test
fun editRejectsDuplicateOfAnotherActiveItem() {
    assertEquals(null, editPlannedObligation(listOf("Appel", "Facture"), emptyList(), oldMotif = "Facture", newLabel = "appel"))
}

@Test
fun editRejectsDuplicateOfACompletedItem() {
    assertEquals(null, editPlannedObligation(listOf("Appel"), listOf("Facture"), oldMotif = "Appel", newLabel = "facture"))
}

@Test
fun editRejectsWhenOldMotifIsAbsent() {
    assertEquals(null, editPlannedObligation(listOf("a"), emptyList(), oldMotif = "zzz", newLabel = "x"))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.PlannedObligationsTest"`
Expected: FAIL — `editPlannedObligation` unresolved reference (compilation error).

- [ ] **Step 3: Implement `editPlannedObligation`**

Append to `PlannedObligations.kt` (after `markObligationCompleted`, before the encode helpers):

```kotlin
/**
 * Rename the active obligation matching [oldMotif] to [newLabel], preserving its position.
 * Returns the new active list, or null when the edit must be rejected: [oldMotif] is absent,
 * [newLabel] is blank after sanitize, the sanitized label is unchanged, or it case-insensitively
 * duplicates another active entry or any completed entry (which would make the string-identity
 * of two items collide). Completed items are never edited here.
 */
fun editPlannedObligation(
    active: List<String>,
    completed: List<String>,
    oldMotif: String,
    newLabel: String,
): List<String>? {
    val index = active.indexOfFirst { matchesPlannedObligation(it, oldMotif) }
    if (index < 0) return null
    val clean = sanitizeLabel(newLabel, 60)
    if (clean.isEmpty()) return null
    if (clean == active[index]) return null
    val target = clean.lowercase()
    val duplicatesAnotherActive = active.withIndex().any { (i, entry) -> i != index && entry.lowercase() == target }
    val duplicatesCompleted = completed.any { it.lowercase() == target }
    if (duplicatesAnotherActive || duplicatesCompleted) return null
    return active.toMutableList().also { it[index] = clean }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.PlannedObligationsTest"`
Expected: PASS (all tests green).

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/PlannedObligations.kt \
        core/src/commonTest/kotlin/fr/dayview/app/PlannedObligationsTest.kt
git commit -m "Add editPlannedObligation pure logic"
```

---

### Task 2: Controller `editPlannedObligation` method

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (add method near `completePlannedObligation`, ~line 685)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `editPlannedObligation(active, completed, oldMotif, newLabel): List<String>?` (Task 1); private `commitPlannedObligations(active: List<String>, completed: List<String>)`; `state.plannedObligationsToday`, `state.plannedObligationsCompletedToday`.
- Produces: `fun editPlannedObligation(oldMotif: String, newLabel: String)` on `DayViewController`.

- [ ] **Step 1: Write the failing tests**

Add to `DayViewControllerTest.kt` (inside `class DayViewControllerTest`, near the other `PlannedObligation` tests ~line 545). `testController` and `t(...)`/`dayKeyOf(...)` are existing test helpers used by the surrounding tests:

```kotlin
@Test
fun editPlannedObligationRenamesInPlaceAndPersists() {
    val preferences = InMemoryDayPreferences()
    val now = 1_800_000_000_000L
    val controller = testController(preferences, now)
    controller.addPlannedObligation("Appel")
    controller.addPlannedObligation("Facture")

    controller.editPlannedObligation(oldMotif = "Appel", newLabel = "Appel client")

    assertEquals(listOf("Appel client", "Facture"), controller.state.plannedObligationsToday)
    assertEquals(listOf("Appel client", "Facture"), preferences.current.plannedObligations)
}

@Test
fun editPlannedObligationIgnoresRejectedEdits() {
    val preferences = InMemoryDayPreferences()
    val now = 1_800_000_000_000L
    val controller = testController(preferences, now)
    controller.addPlannedObligation("Appel")
    controller.addPlannedObligation("Facture")

    controller.editPlannedObligation(oldMotif = "Appel", newLabel = "  ")      // blank
    controller.editPlannedObligation(oldMotif = "Appel", newLabel = "facture") // duplicate

    assertEquals(listOf("Appel", "Facture"), controller.state.plannedObligationsToday)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `editPlannedObligation` is not a member of `DayViewController` (compilation error).

- [ ] **Step 3: Implement the controller method**

Add to `DayViewController.kt` immediately after `completePlannedObligation` (before `private fun commitPlannedObligations`):

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

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Add controller editPlannedObligation routing through commit funnel"
```

---

### Task 3: Editable field in the obligations dialog content

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` (add one tag)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt` (`PlannedObligationsContent` + `PlannedObligationsDialog`)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt`

**Interfaces:**
- Consumes: `GoalTextField(value, semanticLabel, placeholder, onValueChange, onFocusLost, modifier)` (in `DayViewTodayScreen.kt`, same package); string resource `planned_obligation_motif_label`; `DayViewTestTags.PlannedObligationLabel` (added below).
- Produces: `PlannedObligationsContent(..., onEdit: (String, String) -> Unit, ...)` and `PlannedObligationsDialog(..., onEdit: (String, String) -> Unit, ...)`. `onEdit` receives `(oldMotif, newLabel)`.

- [ ] **Step 1: Add the test tag**

In `DayViewTestTags.kt`, after `PlannedObligationInput` (line 56) add:

```kotlin
    const val PlannedObligationLabel = "plannedObligationLabel"
```

- [ ] **Step 2: Write the failing UI tests**

Add to `PlannedObligationsUiTest.kt`. Add these imports at the top of the file (next to the existing `androidx.compose.ui.test.*` imports):

```kotlin
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
```

Then add these test methods inside `class PlannedObligationsUiTest`:

```kotlin
@Test
fun editingAnActiveLabelReportsOldAndNew() = runComposeUiTest {
    var edited: Pair<String, String>? = null
    setContent {
        DayViewTheme {
            PlannedObligationsContent(
                obligations = listOf("Appel client"),
                onAdd = {},
                onComplete = {},
                onRemove = {},
                onEdit = { old, new -> edited = old to new },
                onDismiss = {},
            )
        }
    }
    onNodeWithTag(DayViewTestTags.PlannedObligationLabel).performTextReplacement("Appel fournisseur")
    onNodeWithTag(DayViewTestTags.PlannedObligationLabel).performImeAction() // Done clears focus -> onFocusLost
    assertEquals("Appel client" to "Appel fournisseur", edited)
}

@Test
fun clearingAnActiveLabelRevertsToTheOldValue() = runComposeUiTest {
    setContent {
        DayViewTheme {
            PlannedObligationsContent(
                obligations = listOf("Appel client"),
                onAdd = {},
                onComplete = {},
                onRemove = {},
                onEdit = { _, _ -> },
                onDismiss = {},
            )
        }
    }
    onNodeWithTag(DayViewTestTags.PlannedObligationLabel).performTextReplacement("")
    onNodeWithTag(DayViewTestTags.PlannedObligationLabel).performImeAction()
    onNodeWithTag(DayViewTestTags.PlannedObligationLabel).assert(hasText("Appel client"))
}

@Test
fun completedObligationsAreNotEditable() = runComposeUiTest {
    setContent {
        DayViewTheme {
            PlannedObligationsContent(
                obligations = emptyList(),
                completedObligations = listOf("Appel client"),
                onAdd = {},
                onComplete = {},
                onRemove = {},
                onEdit = { _, _ -> },
                onDismiss = {},
            )
        }
    }
    onNodeWithTag(DayViewTestTags.PlannedObligationLabel).assertDoesNotExist()
}
```

Note: the existing UI tests in this file construct `PlannedObligationsContent` without `onEdit`. Give `onEdit` a default value in Step 4 so those existing call sites keep compiling.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.PlannedObligationsUiTest"`
Expected: FAIL — `onEdit` is not a parameter of `PlannedObligationsContent` (compilation error), and `PlannedObligationLabel` node not found.

- [ ] **Step 4: Add `onEdit` and render the editable field**

In `PlannedObligationsUi.kt`:

1. Add the `key` import next to the other `androidx.compose.runtime.*` imports:

```kotlin
import androidx.compose.runtime.key
```

2. Add `GoalTextField` import (it lives in the same package `fr.dayview.app`, so no import is needed — it is `internal` in `DayViewTodayScreen.kt`). Confirm no import line is added for it.

3. Add `onEdit` to `PlannedObligationsContent`'s signature (with a default so existing tests/callers compile), placed after `onRemove`:

```kotlin
internal fun PlannedObligationsContent(
    obligations: List<String>,
    completedObligations: List<String> = emptyList(),
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (String, String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
```

4. Replace the active-item `Row` block (the `obligations.forEach { motif -> Row(...) { Text(motif, ...) ... } }` currently at lines 109–136) with:

```kotlin
        obligations.forEach { motif ->
            key(motif) {
                var draft by remember(motif) { mutableStateOf(motif) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoalTextField(
                        value = draft,
                        semanticLabel = stringResource(Res.string.planned_obligation_motif_label),
                        placeholder = stringResource(Res.string.planned_obligation_motif_placeholder),
                        onValueChange = { draft = it },
                        onFocusLost = {
                            if (draft != motif) onEdit(motif, draft)
                            draft = motif // revert; an accepted edit recreates this row via key(motif)
                        },
                        modifier = Modifier.weight(1f).testTag(DayViewTestTags.PlannedObligationLabel),
                    )
                    Text(
                        "✕",
                        color = colors.muted,
                        fontSize = 14.sp,
                        modifier = Modifier.minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = removeLabel) { onRemove(motif) }
                            .testTag(DayViewTestTags.PlannedObligationRemove)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    FocusActionButton(
                        label = stringResource(Res.string.planned_obligation_done_button),
                        color = colors.mint,
                        modifier = Modifier.testTag(DayViewTestTags.PlannedObligationDone),
                        onClick = { onComplete(motif) },
                    )
                }
            }
        }
```

5. Thread `onEdit` through the `PlannedObligationsDialog` wrapper (lines 49–60):

```kotlin
internal fun PlannedObligationsDialog(
    obligations: List<String>,
    completedObligations: List<String>,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        PlannedObligationsContent(obligations, completedObligations, onAdd, onComplete, onRemove, onEdit, onDismiss)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.PlannedObligationsUiTest"`
Expected: PASS (new tests green; existing tests still green).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt
git commit -m "Render active essentials as editable fields"
```

---

### Task 4: Wire the edit action from screen to controller

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (actions struct ~line 216; dialog call ~line 435)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (actions construction ~line 566)

**Interfaces:**
- Consumes: `DayViewController.editPlannedObligation(oldMotif, newLabel)` (Task 2); `PlannedObligationsDialog(..., onEdit, ...)` (Task 3).
- Produces: `editPlannedObligation: (String, String) -> Unit` field on the today-screen actions data class, wired to the controller.

- [ ] **Step 1: Add the action field**

In `DayViewTodayScreen.kt`, add to the actions `data class` after `completePlannedObligation` (line 216):

```kotlin
    val editPlannedObligation: (String, String) -> Unit = { _, _ -> },
```

- [ ] **Step 2: Pass it to the dialog**

In `DayViewTodayScreen.kt`, in the `PlannedObligationsDialog(...)` call (line 435), add the `onEdit` argument after `onRemove`:

```kotlin
            PlannedObligationsDialog(
                obligations = state.plannedObligationsToday,
                completedObligations = state.plannedObligationsCompletedToday,
                onAdd = actions.addPlannedObligation,
                onComplete = actions.completePlannedObligation,
                onRemove = actions.removePlannedObligation,
                onEdit = actions.editPlannedObligation,
                onDismiss = { showObligations = false },
            )
```

- [ ] **Step 3: Wire the controller in App.kt**

In `App.kt`, in the actions construction (after `completePlannedObligation = ...`, line 566), add:

```kotlin
                                    editPlannedObligation = { oldMotif, newLabel ->
                                        controller.editPlannedObligation(oldMotif, newLabel)
                                    },
```

- [ ] **Step 4: Verify the app compiles and the full gate passes**

Run: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint violations, all tests pass, no stderr.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "Wire editable essentials action to controller"
```

---

## Self-Review notes

- **Spec coverage:** pure logic (Task 1) ↔ spec §1 + all 7 logic tests; controller (Task 2) ↔ spec §2; editable field + revert + read-only completed (Task 3) ↔ spec §3 + UI tests; wiring (Task 4) ↔ spec §4. All spec sections mapped.
- **Type consistency:** `editPlannedObligation` signature identical in Tasks 1→2; `onEdit: (String, String) -> Unit` identical across content, dialog, actions field, and App wiring; test tag `PlannedObligationLabel` defined in Task 3 Step 1 and used consistently.
- **Revert mechanism:** single mechanism — unconditional `draft = motif` after the optional `onEdit` call; accepted edits change `motif` and force `key(motif)` recreation, so the reset only surfaces on rejected edits (matches spec's "Revert semantics").
- **Duplicate handling in UI test:** the stateless test harness never mutates the list, so every edit "appears rejected" and reverts — this is why `clearingAnActiveLabelRevertsToTheOldValue` asserts the old value; acceptance is proven by the controller/pure tests instead.
