# Obligations Modal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the "Obligations du jour" section off the main screen into a dedicated modal opened from a compact counter chip, add per-row deletion, and fix the unreadable motif contrast.

**Architecture:** Refactor the inline `PlannedObligationsSection` into a testable `PlannedObligationsContent` wrapped by a `PlannedObligationsDialog` (same `Dialog` idiom as `DetourListDialog`). A new `PlannedObligationsChip` on the main screen (next to the detour row) opens the modal. Deletion reuses the already-existing `removePlannedObligation` controller method, newly plumbed through `DayViewScreenActions`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, compose-resources for i18n, Compose UI test (`runComposeUiTest`) on the desktop target.

## Global Constraints

- ktlint is enforced: `./gradlew ktlintCheck` must pass (run `ktlintFormat` to auto-fix).
- Full gate before finishing: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Compose i18n strings use a single `%` and positional args `%1$d`, `%1$s` (see `composeResources/values/strings.xml`). Add every new string to BOTH `values/strings.xml` (English) and `values-fr/strings.xml` (French).
- Desktop UI tests must NOT assert `stringResource` text (unresolved under `runComposeUiTest` on CI) — drive via test tags and callbacks. `Dialog` windows are unreachable by `runComposeUiTest`, so always test the `*Content` composable directly, never the `*Dialog` wrapper.
- Commit messages describe the change only — no reference to Claude/Anthropic/AI, no test-plan section.
- No changes to data model, persistence, the obligation cap (`MAX_PLANNED_OBLIGATIONS = 3`), or the completion-as-detour flow.

---

## File Structure

- `composeApp/src/commonMain/composeResources/values/strings.xml` — add 3 English strings.
- `composeApp/src/commonMain/composeResources/values-fr/strings.xml` — add 3 French strings.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` — add 2 tags.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt` — add `PlannedObligationsChip`, `PlannedObligationsContent`, `PlannedObligationsDialog`; later remove `PlannedObligationsSection`.
- `composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt` — migrate tests to `PlannedObligationsContent`, add removal + chip tests.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` — add `removePlannedObligation` to `DayViewScreenActions`; swap inline section for chip; render dialog; add `showObligations` state.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` — wire `removePlannedObligation`.
- `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt` — wire `removePlannedObligation` in both action bundles.

---

## Task 1: Modal components (chip, content, dialog) with deletion and contrast fix

Builds the new composables **alongside** the existing `PlannedObligationsSection` (not deleted yet) so the build stays green. Migrates the existing UI tests onto the new content and adds a removal test and a chip test.

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml:253`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml:252`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt:25`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt`

**Interfaces:**
- Consumes: `MAX_PLANNED_OBLIGATIONS: Int`, `GoalTextField(value, semanticLabel, placeholder, onValueChange, modifier)`, `FocusActionButton(label, color, modifier, enabled, filled, onClick)`, `LocalDayViewColors`, `DayViewTestTags`.
- Produces:
  - `PlannedObligationsChip(count: Int, cap: Int, onOpen: () -> Unit, modifier: Modifier = Modifier)`
  - `PlannedObligationsContent(obligations: List<String>, onAdd: (String) -> Unit, onComplete: (String) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit)`
  - `PlannedObligationsDialog(obligations: List<String>, onAdd: (String) -> Unit, onComplete: (String) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit)`
  - Test tags `DayViewTestTags.PlannedObligationsChip`, `DayViewTestTags.PlannedObligationRemove`.

---

- [ ] **Step 1: Add the English strings**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, inside the `<!-- Planned obligations -->` block (after line 253, before `</resources>`):

```xml
    <string name="planned_obligations_chip">Obligations %1$d/%2$d</string>
    <string name="planned_obligations_open_label">Open today\'s obligations</string>
    <string name="planned_obligation_remove_label">Remove obligation</string>
```

- [ ] **Step 2: Add the French strings**

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, inside the `<!-- Planned obligations -->` block (after line 252, before `</resources>`):

```xml
    <string name="planned_obligations_chip">Obligations %1$d/%2$d</string>
    <string name="planned_obligations_open_label">Ouvrir les obligations du jour</string>
    <string name="planned_obligation_remove_label">Supprimer l\'obligation</string>
```

- [ ] **Step 3: Add the test tags**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`, after the line `const val PlannedObligationDone = "plannedObligationDone"` (line 25):

```kotlin
    const val PlannedObligationsChip = "plannedObligationsChip"
    const val PlannedObligationRemove = "plannedObligationRemove"
```

- [ ] **Step 4: Replace the body of `PlannedObligationsUi.kt`**

Overwrite `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt` with the following. This KEEPS `PlannedObligationsSection` (removed in Task 2) and ADDS the chip, content, and dialog.

```kotlin
package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.detour_close_button
import fr.dayview.app.generated.resources.planned_obligation_add_button
import fr.dayview.app.generated.resources.planned_obligation_done_button
import fr.dayview.app.generated.resources.planned_obligation_motif_label
import fr.dayview.app.generated.resources.planned_obligation_motif_placeholder
import fr.dayview.app.generated.resources.planned_obligation_remove_label
import fr.dayview.app.generated.resources.planned_obligations_chip
import fr.dayview.app.generated.resources.planned_obligations_open_label
import fr.dayview.app.generated.resources.planned_obligations_title
import org.jetbrains.compose.resources.stringResource

/** Compact main-screen entry point that opens the obligations modal; always visible. */
@Composable
internal fun PlannedObligationsChip(
    count: Int,
    cap: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Text(
        stringResource(Res.string.planned_obligations_chip, count, cap),
        color = colors.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.planned_obligations_open_label), onClick = onOpen)
            .testTag(DayViewTestTags.PlannedObligationsChip)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Modal wrapper around [PlannedObligationsContent] (untestable Dialog window; test the content). */
@Composable
internal fun PlannedObligationsDialog(
    obligations: List<String>,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        PlannedObligationsContent(obligations, onAdd, onComplete, onRemove, onDismiss)
    }
}

/**
 * The day's must-do obligations: at most [MAX_PLANNED_OBLIGATIONS], each completable via
 * FAIT or deletable via the ✕. Split out of the Dialog so Compose UI tests can drive it.
 */
@Composable
internal fun PlannedObligationsContent(
    obligations: List<String>,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    var draft by remember { mutableStateOf("") }
    val atCap = obligations.size >= MAX_PLANNED_OBLIGATIONS
    val removeLabel = stringResource(Res.string.planned_obligation_remove_label)

    Column(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.planned_obligations_title),
            color = colors.amber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp,
        )

        obligations.forEach { motif ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    motif,
                    color = colors.cloud,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
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
        }

        FocusActionButton(
            stringResource(Res.string.detour_close_button),
            colors.muted,
            modifier = Modifier.fillMaxWidth(),
            onClick = onDismiss,
        )
    }
}

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

- [ ] **Step 5: Rewrite the UI test file to drive the new content**

Overwrite `composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
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
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("a", "b", "c"),
                    onAdd = {},
                    onComplete = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationInput).assertDoesNotExist()
    }

    @Test
    fun doneReportsTheRowMotif() = runComposeUiTest {
        var completed: String? = null
        setContent {
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("Appel client"),
                    onAdd = {},
                    onComplete = { completed = it },
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationDone).performClick()
        assertEquals("Appel client", completed)
    }

    @Test
    fun removeReportsTheRowMotif() = runComposeUiTest {
        var removed: String? = null
        setContent {
            DayViewTheme {
                PlannedObligationsContent(
                    obligations = listOf("Appel client"),
                    onAdd = {},
                    onComplete = {},
                    onRemove = { removed = it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationRemove).performClick()
        assertEquals("Appel client", removed)
    }

    @Test
    fun chipOpenFires() = runComposeUiTest {
        var opened = false
        setContent {
            DayViewTheme {
                PlannedObligationsChip(count = 2, cap = 3, onOpen = { opened = true })
            }
        }
        onNodeWithTag(DayViewTestTags.PlannedObligationsChip).performClick()
        assertEquals(true, opened)
    }
}
```

> Note: `PlannedObligationsContent` is wrapped in `DayViewTheme {}` because it reads `LocalDayViewColors`; the previous `PlannedObligationsSection` tests relied on a default that the content's panel/border styling also needs. `DayViewTheme` is the same helper used by `WideDayView` in `UiTestSupport.kt`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.PlannedObligationsUiTest"`
Expected: PASS (4 tests: `addFieldHiddenAtTheCap`, `doneReportsTheRowMotif`, `removeReportsTheRowMotif`, `chipOpenFires`).

- [ ] **Step 7: Run ktlint**

Run: `./gradlew ktlintCheck`
Expected: PASS (no violations). If it fails on import ordering/formatting, run `./gradlew ktlintFormat` and re-run `ktlintCheck`.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/PlannedObligationsUiTest.kt
git commit -m "Add obligations modal content, chip, and row deletion"
```

---

## Task 2: Wire the modal into the main screen and remove the inline section

Plumbs `removePlannedObligation` through the actions bundle, replaces the two inline `PlannedObligationsSection` call sites with the chip, renders the dialog, and deletes the now-unused section.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt:186` (actions field), `:207` (state), `:273-277` and `:335-339` (call sites), `:388` (dialog render)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt:257`
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt:100` and `:144`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt` (delete `PlannedObligationsSection`)

**Interfaces:**
- Consumes (from Task 1): `PlannedObligationsChip(count, cap, onOpen, modifier)`, `PlannedObligationsDialog(obligations, onAdd, onComplete, onRemove, onDismiss)`, `MAX_PLANNED_OBLIGATIONS`.
- Consumes (existing): `DayViewController.removePlannedObligation(motif: String)` (already defined at `DayViewController.kt:393`).
- Produces: `DayViewScreenActions.removePlannedObligation: (String) -> Unit`.

---

- [ ] **Step 1: Add `removePlannedObligation` to `DayViewScreenActions`**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`, in the `DayViewScreenActions` data class, after `val addPlannedObligation: (String) -> Unit,` (line 185):

```kotlin
    val removePlannedObligation: (String) -> Unit,
```

- [ ] **Step 2: Wire it in `App.kt`**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`, after `addPlannedObligation = { controller.addPlannedObligation(it) },` (line 257):

```kotlin
                                removePlannedObligation = { controller.removePlannedObligation(it) },
```

- [ ] **Step 3: Wire it in both `UiTestSupport.kt` action bundles**

In `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt`, after the stub `addPlannedObligation = {},` (line 100):

```kotlin
    removePlannedObligation = {},
```

And after the controller-backed `addPlannedObligation = { controller.addPlannedObligation(it) },` (line 144):

```kotlin
    removePlannedObligation = { controller.removePlannedObligation(it) },
```

- [ ] **Step 4: Add the `showObligations` state**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`, in `DayViewScreen`, after `var obligationToComplete by remember { mutableStateOf<String?>(null) }` (line 207):

```kotlin
    var showObligations by remember { mutableStateOf(false) }
```

- [ ] **Step 5: Replace the wide-layout section with the chip**

In `DayViewScreen` (wide branch), replace this block (lines 273-277):

```kotlin
                        PlannedObligationsSection(
                            obligations = state.plannedObligationsToday,
                            onAdd = actions.addPlannedObligation,
                            onComplete = { obligationToComplete = it },
                        )
```

with:

```kotlin
                        PlannedObligationsChip(
                            count = state.plannedObligationsToday.size,
                            cap = MAX_PLANNED_OBLIGATIONS,
                            onOpen = { showObligations = true },
                        )
```

- [ ] **Step 6: Replace the compact-layout section with the chip**

In `DayViewScreen` (compact branch), replace this block (lines 335-339):

```kotlin
                PlannedObligationsSection(
                    obligations = state.plannedObligationsToday,
                    onAdd = actions.addPlannedObligation,
                    onComplete = { obligationToComplete = it },
                )
```

with:

```kotlin
                PlannedObligationsChip(
                    count = state.plannedObligationsToday.size,
                    cap = MAX_PLANNED_OBLIGATIONS,
                    onOpen = { showObligations = true },
                )
```

- [ ] **Step 7: Render the dialog**

In `DayViewScreen`, immediately after the closing `}` of the `if (showDetourList) { ... }` block (line 388), add:

```kotlin
        if (showObligations) {
            PlannedObligationsDialog(
                obligations = state.plannedObligationsToday,
                onAdd = actions.addPlannedObligation,
                onComplete = {
                    obligationToComplete = it
                    showObligations = false
                },
                onRemove = actions.removePlannedObligation,
                onDismiss = { showObligations = false },
            )
        }
```

- [ ] **Step 8: Delete the now-unused `PlannedObligationsSection`**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt`, delete the entire `PlannedObligationsSection` function (the last function in the file, added/kept in Task 1). Leave `PlannedObligationsChip`, `PlannedObligationsDialog`, and `PlannedObligationsContent`.

- [ ] **Step 9: Verify the build compiles and the desktop tests pass**

Run: `./gradlew :composeApp:desktopTest`
Expected: PASS. This compiles `commonMain` + `desktopTest`; the swapped call sites and new actions field must resolve. `PlannedObligationsUiTest` from Task 1 stays green.

- [ ] **Step 10: Run the full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS with no errors or stderr. If ktlint complains, `./gradlew ktlintFormat` then re-run.

- [ ] **Step 11: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/PlannedObligationsUi.kt
git commit -m "Move daily obligations into a modal opened from a counter chip"
```

---

## Manual Verification (after Task 2)

Run the desktop app (`./gradlew :composeApp:run`) and confirm:
- The main screen shows a compact "Obligations n/3" chip near the "+ DÉTOUR" row instead of the inline section.
- Clicking the chip opens the modal; obligation motifs are clearly readable (light `cloud` text, not near-black).
- Adding an obligation works; the chip counter increments.
- The ✕ removes an obligation immediately; the counter decrements.
- FAIT closes the modal and opens the pre-filled detour capture dialog (unchanged behavior).
- At 3 obligations the add field is hidden.

## Self-Review Notes

- **Spec coverage:** chip entry point (Task 2 steps 5-6), always-visible counter incl. 0 (chip renders `count/cap` unconditionally), modal (Task 1 content/dialog), ✕ deletion wired to existing `removePlannedObligation` (Task 1 UI + Task 2 plumbing), contrast fix (`colors.cloud` in content), i18n both locales, test tags, testability split, tests incl. removal — all covered.
- **Type consistency:** `PlannedObligationsContent`/`PlannedObligationsDialog` share the exact `(obligations, onAdd, onComplete, onRemove, onDismiss)` signature; `DayViewScreenActions.removePlannedObligation: (String) -> Unit` matches `controller.removePlannedObligation(motif: String)` and `actions.removePlannedObligation` usage.
- **No placeholders:** every code and command step is concrete.
