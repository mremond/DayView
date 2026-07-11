# Android Single-Page Home + Focus Popup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the default (idle) Android/phone home screen fit on a single page by moving the bulky Focus-creation form and Global-Goal editor into bottom-sheet popups, while keeping a running Focus visible inline.

**Architecture:** All changes are confined to the compact (`else`) branch of `DayViewScreen` in `DayViewTodayScreen.kt`; the wide (>= 780dp / desktop) branch is untouched. The idle Focus creation body is extracted into a reusable composable shared by the desktop inline panel and the new phone bottom sheet. Popup visibility is transient local UI state (`remember`), so `DayViewController` and `App.kt` are unchanged.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Material3 (`ModalBottomSheet`). Tests use `kotlin.test` in `commonTest`, run via the Gradle `desktopTest` task.

## Global Constraints

- **Scope:** Modify only the compact branch of `DayViewScreen`. The `wide` branch must render byte-for-byte identically to before.
- **No business-logic changes:** Do not modify `DayViewController` or `App.kt`. Reuse existing `DayViewScreenActions` callbacks.
- **No new navigation destination:** Popup visibility is a local `remember { mutableStateOf<CompactSheet?>(null) }`, not a new `DayViewDestination`.
- **UI language is French**, matching all existing copy (e.g. `DÉMARRER LE FOCUS`, `OBJECTIF GLOBAL`).
- **Keep `verticalScroll` on the compact page** as a safety net; the idle screen is sized to fit without needing it.
- Colors come from `LocalDayViewColors.current` (fields: `ink`, `glow`, `panel`, `overlay`, `cloud`, `muted`, `mint`, `amber`, `red`).
- `ModalBottomSheet` and `rememberModalBottomSheetState()` require `@OptIn(ExperimentalMaterial3Api::class)`.

---

### Task 1: `formatGoalSummaryLine` pure helper

Extracts the read-only text shown on the new compact Goal row. Pure function → unit-tested.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/GlobalGoal.kt` (append at end, after `formatGoalWorkingHours` which ends at line 104)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/GlobalGoalTest.kt`

**Interfaces:**
- Consumes: existing `fun formatGoalWorkingHours(workingMillis: Long, deadlineReached: Boolean): String`
- Produces: `fun formatGoalSummaryLine(title: String, deadlineMillis: Long?, workingMillis: Long, deadlineReached: Boolean): String` — returns the ` · `-joined value portion of the compact goal row (title and/or remaining-hours). Returns `""` only when `title` is blank and `deadlineMillis` is null (caller then shows the empty-state placeholder).

- [ ] **Step 1: Write the failing test**

Append to `GlobalGoalTest.kt` (inside the existing test class):

```kotlin
    @Test
    fun goalSummaryJoinsTitleAndRemainingHours() {
        val line = formatGoalSummaryLine(
            title = "Livrer la v2",
            deadlineMillis = 1_000L,
            workingMillis = 12 * 3_600_000L,
            deadlineReached = false,
        )
        assertEquals("Livrer la v2 · Encore 12 h", line)
    }

    @Test
    fun goalSummaryShowsRemainingHoursWhenTitleBlank() {
        val line = formatGoalSummaryLine(
            title = "",
            deadlineMillis = 1_000L,
            workingMillis = 12 * 3_600_000L,
            deadlineReached = false,
        )
        assertEquals("Encore 12 h", line)
    }

    @Test
    fun goalSummaryShowsTitleOnlyWhenNoDeadline() {
        val line = formatGoalSummaryLine(
            title = "Livrer la v2",
            deadlineMillis = null,
            workingMillis = 0L,
            deadlineReached = false,
        )
        assertEquals("Livrer la v2", line)
    }

    @Test
    fun goalSummaryEmptyWhenNothingSet() {
        val line = formatGoalSummaryLine(
            title = "",
            deadlineMillis = null,
            workingMillis = 0L,
            deadlineReached = false,
        )
        assertEquals("", line)
    }
```

If `GlobalGoalTest.kt` does not already import `assertEquals`/`Test`, they are already present (the file has existing tests using them). Confirm the imports `import kotlin.test.Test` and `import kotlin.test.assertEquals` exist at the top; add any that is missing.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.GlobalGoalTest"`
Expected: FAIL — compilation error, `formatGoalSummaryLine` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Append to `GlobalGoal.kt`:

```kotlin
fun formatGoalSummaryLine(
    title: String,
    deadlineMillis: Long?,
    workingMillis: Long,
    deadlineReached: Boolean,
): String = listOfNotNull(
    title.ifBlank { null },
    deadlineMillis?.let { formatGoalWorkingHours(workingMillis, deadlineReached) },
).joinToString(" · ")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.GlobalGoalTest"`
Expected: PASS (all four new tests plus the pre-existing ones).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/GlobalGoal.kt composeApp/src/commonTest/kotlin/fr/dayview/app/GlobalGoalTest.kt
git commit -m "feat: add formatGoalSummaryLine helper for compact goal row"
```

---

### Task 2: Extract `FocusCreationContent` (structural refactor, no behavior change)

The IDLE body of `FocusPanel` (the creation form) is pulled into its own composable so the desktop inline panel and the upcoming phone bottom sheet can both render it. Desktop output must stay identical.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (the `FocusPanel` composable, lines 416–703; specifically the final `else` block at lines 633–701)

**Interfaces:**
- Consumes: existing `PomodoroProgress`, `FocusClosureOutcome`, `GoalTextField`, `TimeButton`, `FocusActionButton`, `LocalDayViewColors`.
- Produces: `@Composable fun FocusCreationContent(progress: PomodoroProgress, intention: String, lastClosure: FocusClosureOutcome?, onIntentionChange: (String) -> Unit, onDurationChange: (Int) -> Unit, onStart: () -> Unit)` — renders the last-closure chip, the "À la fin de ce Focus…" label, intention field, duration stepper, and the `DÉMARRER LE FOCUS` button. `onStart` is invoked when the start button is pressed.

- [ ] **Step 1: Add the new `FocusCreationContent` composable**

Add this new private composable immediately after `FocusPanel` (after line 703) in `DayViewTodayScreen.kt`. Its body is the exact contents of the current `else` block (lines 634–700), unchanged:

```kotlin
@Composable
private fun FocusCreationContent(
    progress: PomodoroProgress,
    intention: String,
    lastClosure: FocusClosureOutcome?,
    onIntentionChange: (String) -> Unit,
    onDurationChange: (Int) -> Unit,
    onStart: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    if (lastClosure != null) {
        val closureLabel = when (lastClosure) {
            FocusClosureOutcome.COMPLETED -> "TERMINÉ"
            FocusClosureOutcome.PROGRESSED -> "AVANCÉ"
            FocusClosureOutcome.TO_RESUME -> "À REPRENDRE"
        }
        Text(
            "FOCUS CLÔTURÉ · $closureLabel",
            color = colors.mint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .9.sp,
        )
        Spacer(Modifier.height(10.dp))
    }
    Text(
        "À LA FIN DE CE FOCUS, J’AURAI…",
        color = colors.muted,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(7.dp))
    GoalTextField(
        value = intention,
        semanticLabel = "Intention du Focus",
        placeholder = "Ex. terminé le plan de la présentation",
        onValueChange = onIntentionChange,
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TimeButton(
            label = "−",
            enabled = progress.durationMinutes > 5,
            onClickLabel = "Diminuer la durée du Focus de 5 minutes",
            valueDescription = "Durée du Focus : ${progress.durationMinutes} minutes",
        ) { onDurationChange(-5) }
        Spacer(Modifier.width(18.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(progress.durationMinutes.toString(), color = colors.cloud, fontSize = 28.sp, fontWeight = FontWeight.Light)
            Text("MINUTES", color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.width(18.dp))
        TimeButton(
            label = "+",
            enabled = progress.durationMinutes < 180,
            onClickLabel = "Augmenter la durée du Focus de 5 minutes",
            valueDescription = "Durée du Focus : ${progress.durationMinutes} minutes",
        ) { onDurationChange(5) }
    }
    Spacer(Modifier.height(13.dp))
    FocusActionButton(
        "DÉMARRER LE FOCUS",
        colors.amber,
        modifier = Modifier.fillMaxWidth(),
        enabled = intention.isNotBlank(),
        filled = true,
        onClick = onStart,
    )
    if (intention.isBlank()) {
        Spacer(Modifier.height(7.dp))
        Text("Écrivez une intention pour démarrer.", color = colors.muted, fontSize = 10.sp)
    }
}
```

- [ ] **Step 2: Replace the `FocusPanel` else-block body with a call to it**

In `FocusPanel`, replace the entire final `else {` block (current lines 633–701) with:

```kotlin
        } else {
            FocusCreationContent(
                progress = progress,
                intention = intention,
                lastClosure = lastClosure,
                onIntentionChange = onIntentionChange,
                onDurationChange = onDurationChange,
                onStart = onStart,
            )
        }
```

- [ ] **Step 3: Verify the project compiles**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL. (No behavior change — `FocusPanel` renders identically; the wide layout and current compact layout are visually unchanged.)

- [ ] **Step 4: Run the existing test suite**

Run: `./gradlew :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "refactor: extract FocusCreationContent from FocusPanel"
```

---

### Task 3: Single-page compact home with Goal + Focus bottom sheets

Rewrite the compact (`else`) branch of `DayViewScreen` into the single-page layout: condensed subtitle, tap-to-edit Goal row, Focus entry button (or inline running session), and % line — plus the two `ModalBottomSheet` popups. The wide branch is left as-is.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
  - the `else` block of `DayViewScreen` (current lines 158–189)
  - add new imports and new private composables

**Interfaces:**
- Consumes: `formatGoalSummaryLine` (Task 1), `FocusCreationContent` (Task 2), existing `FocusPanel`, `GlobalGoalPanel`, `GoalTextField`, `CountdownCircle`, `calculateGoalWorkingMillis`, `DayViewScreenActions`, `FocusReminderUiState`, `PomodoroStatus`.
- Produces: local enum `private enum class CompactSheet { FOCUS, GOAL }`; private composables `CompactTodayContent`, `CompactGoalRow`, `FocusEntryButton`, `GoalEditorContent`.

- [ ] **Step 1: Add imports**

At the top of `DayViewTodayScreen.kt`, add these imports (keep alphabetical grouping consistent with the file):

```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
```

- [ ] **Step 2: Add the `CompactSheet` enum and `CompactTodayContent` composable**

Add near the top of the file (after the `FocusReminderUiState` data class, around line 80):

```kotlin
private enum class CompactSheet { FOCUS, GOAL }
```

Add this composable (place it right after `DayViewScreen`, before `Header`). It owns the sheet state and renders the single-page column plus the popups:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactTodayContent(
    state: DayViewUiState,
    actions: DayViewScreenActions,
    reminders: FocusReminderUiState,
) {
    val colors = LocalDayViewColors.current
    val progress = state.dayProgress
    val pomodoro = state.pomodoroProgress
    var openSheet by remember { mutableStateOf<CompactSheet?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                !progress.hasStarted -> "La journée n’a pas commencé."
                progress.isFinished -> "Le temps prévu est écoulé."
                progress.remainingRatio < .2f -> "La journée touche à sa fin."
                else -> "Gardez le cap, sans pression."
            },
            color = colors.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        CompactGoalRow(
            title = state.goalTitle,
            deadlineMillis = state.goalDeadlineMillis,
            nowMillis = state.nowMillis,
            workStartMinutes = progress.startHour * 60 + progress.startMinute,
            workEndMinutes = progress.endHour * 60 + progress.endMinute,
            onClick = { openSheet = CompactSheet.GOAL },
        )
        Spacer(Modifier.height(14.dp))

        if (pomodoro.status == PomodoroStatus.IDLE) {
            FocusEntryButton(
                lastClosure = state.lastFocusClosure,
                onClick = { openSheet = CompactSheet.FOCUS },
            )
        } else {
            FocusPanel(
                progress = pomodoro,
                intention = state.focusIntention,
                lastClosure = state.lastFocusClosure,
                onIntentionChange = actions.changeFocusIntention,
                showDriftReminder = reminders.showDriftReminder,
                onDismissDriftReminder = reminders.dismissDriftReminder,
                showResumeRitual = reminders.showResumeRitual,
                onDismissResumeRitual = reminders.dismissResumeRitual,
                onDurationChange = actions.changePomodoroDuration,
                onStart = actions.startPomodoro,
                onStop = actions.stopPomodoro,
                onClose = actions.closePomodoro,
            )
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (progress.isFinished) colors.red else colors.mint, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(
                if (progress.isFinished) "0 % de la journée disponible" else "${progress.percentageRemaining} % de la journée disponible",
                color = colors.muted,
                fontSize = 12.sp,
            )
        }
    }

    if (openSheet == CompactSheet.FOCUS) {
        ModalBottomSheet(
            onDismissRequest = { openSheet = null },
            sheetState = rememberModalBottomSheetState(),
            containerColor = colors.panel,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .imePadding(),
            ) {
                Text("FOCUS", color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Spacer(Modifier.height(14.dp))
                FocusCreationContent(
                    progress = pomodoro,
                    intention = state.focusIntention,
                    lastClosure = state.lastFocusClosure,
                    onIntentionChange = actions.changeFocusIntention,
                    onDurationChange = actions.changePomodoroDuration,
                    onStart = {
                        actions.startPomodoro()
                        openSheet = null
                    },
                )
            }
        }
    }

    if (openSheet == CompactSheet.GOAL) {
        ModalBottomSheet(
            onDismissRequest = {
                actions.commitGoalDeadline()
                openSheet = null
            },
            sheetState = rememberModalBottomSheetState(),
            containerColor = colors.panel,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .imePadding(),
            ) {
                GoalEditorContent(
                    title = state.goalTitle,
                    deadlineText = state.goalDeadlineText,
                    onTitleChange = actions.changeGoalTitle,
                    onDeadlineChange = actions.changeGoalDeadline,
                    onDeadlineCommit = actions.commitGoalDeadline,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Add `CompactGoalRow`, `FocusEntryButton`, and `GoalEditorContent`**

Add these three private composables after `CompactTodayContent`:

```kotlin
@Composable
private fun CompactGoalRow(
    title: String,
    deadlineMillis: Long?,
    nowMillis: Long,
    workStartMinutes: Int,
    workEndMinutes: Int,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val hasGoal = title.isNotBlank() || deadlineMillis != null
    val workingMillis = remember(nowMillis / 60_000, deadlineMillis, workStartMinutes, workEndMinutes) {
        deadlineMillis?.let {
            calculateGoalWorkingMillis(
                nowMillis = nowMillis,
                deadlineMillis = it,
                startMinutesOfDay = workStartMinutes,
                endMinutesOfDay = workEndMinutes,
            )
        } ?: 0L
    }
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 430.dp)
            .background(colors.panel, RoundedCornerShape(14.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasGoal) {
            Text("OBJECTIF", color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                formatGoalSummaryLine(
                    title = title,
                    deadlineMillis = deadlineMillis,
                    workingMillis = workingMillis,
                    deadlineReached = deadlineMillis != null && deadlineMillis <= nowMillis,
                ),
                color = colors.cloud,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                "＋ Définir un objectif",
                color = colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FocusEntryButton(
    lastClosure: FocusClosureOutcome?,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 430.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lastClosure != null) {
            val closureLabel = when (lastClosure) {
                FocusClosureOutcome.COMPLETED -> "TERMINÉ"
                FocusClosureOutcome.PROGRESSED -> "AVANCÉ"
                FocusClosureOutcome.TO_RESUME -> "À REPRENDRE"
            }
            Text(
                "FOCUS CLÔTURÉ · $closureLabel",
                color = colors.mint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .9.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        FocusActionButton(
            "DÉMARRER UN FOCUS",
            colors.amber,
            modifier = Modifier.fillMaxWidth(),
            filled = true,
            onClick = onClick,
        )
    }
}

@Composable
private fun GoalEditorContent(
    title: String,
    deadlineText: String,
    onTitleChange: (String) -> Unit,
    onDeadlineChange: (String) -> Unit,
    onDeadlineCommit: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val deadlineIsValid = deadlineText.isBlank() || parseGoalDeadline(deadlineText) != null
    Text("OBJECTIF GLOBAL", color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
    Spacer(Modifier.height(12.dp))
    GoalTextField(
        value = title,
        semanticLabel = "Objectif du jour",
        placeholder = "Que voulez-vous accomplir ?",
        onValueChange = onTitleChange,
        imeAction = ImeAction.Next,
    )
    Spacer(Modifier.height(9.dp))
    GoalTextField(
        value = deadlineText,
        semanticLabel = "Date limite de l’objectif",
        placeholder = GOAL_DATE_PLACEHOLDER,
        onValueChange = { onDeadlineChange(formatGoalDeadlineInput(it)) },
        isError = !deadlineIsValid,
        keyboardType = KeyboardType.Number,
        onFocusLost = onDeadlineCommit,
    )
    if (!deadlineIsValid) {
        Spacer(Modifier.height(6.dp))
        Text("Format attendu : $GOAL_DATE_PLACEHOLDER", color = colors.red, fontSize = 10.sp)
    }
}
```

- [ ] **Step 4: Replace the compact `else` branch of `DayViewScreen`**

Replace the entire compact `else` block (current lines 158–189, i.e. the `CountdownCircle` + `GlobalGoalPanel` + `SidePanel` sequence) with:

```kotlin
            } else {
                CountdownCircle(progress, state.showSeconds, Modifier.fillMaxWidth().height(compactCountdownHeight))
                Spacer(Modifier.height(12.dp))
                CompactTodayContent(
                    state = state,
                    actions = actions,
                    reminders = reminders,
                )
            }
```

- [ ] **Step 5: Verify the project compiles**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL. If an unused-import or unresolved-reference error appears, resolve it (e.g. `SidePanel` may now be unused only in compact but is still referenced by the wide branch — leave it; do not delete it).

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Verify on Android (visual)**

Build and install the debug app, then confirm the default idle phone screen fits on one page without scrolling and both popups work:

Run: `./gradlew :composeApp:installDebug` (with a device/emulator connected)
Then: `adb shell am start -n fr.dayview.app/.MainActivity`

Manually verify:
1. Idle screen: header, circle, condensed subtitle, Goal row, `DÉMARRER UN FOCUS` button, and % line all fit without scrolling.
2. Tapping the Goal row opens the goal bottom sheet; editing title/deadline persists after dismiss.
3. Tapping `DÉMARRER UN FOCUS` opens the focus bottom sheet; entering an intention enables `DÉMARRER LE FOCUS`; starting dismisses the sheet and shows the running session inline.
4. During a running/paused Focus, the inline session (clock, stop, break UI, drift/resume overlays) renders in place of the button.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "feat: single-page compact home with Focus and Goal bottom sheets"
```

---

## Self-Review

**Spec coverage:**
- "Compact/phone only; wide branch unchanged" → Task 3 Step 4 only replaces the compact `else` block; wide branch and its `SidePanel`/`GlobalGoalPanel` are untouched. ✓
- "No `DayViewController` / `App.kt` changes; reuse actions" → all three tasks only touch `GlobalGoal.kt`, `DayViewTodayScreen.kt`, and a test file. ✓
- "Popup visibility is local `remember` state, no new destination" → `CompactSheet` local state in Task 3 Step 2. ✓
- "Compact composition: header, circle, condensed subtitle, compact Goal row (tap→sheet), Focus entry button/inline session, % line" → Task 3 Steps 2–4. ✓
- "Two `ModalBottomSheet` popups (Focus + Goal)" → Task 3 Step 2. ✓
- "Active/paused Focus renders inline via existing session UI" → Task 3 Step 2 renders `FocusPanel` for non-IDLE status. ✓
- "Keep `verticalScroll` safety net" → unchanged; the compact `pageModifier` at lines 106–108 already applies `verticalScroll` and is not modified. ✓
- "Extract new pure formatting into a tested helper" → Task 1 `formatGoalSummaryLine`. ✓
- "Bottom sheet over dialog; keyboard-friendly" → `ModalBottomSheet` with `imePadding()` in Task 3 Step 2. ✓

**Placeholder scan:** No TBD/TODO; every code step contains complete code. ✓

**Type consistency:** `formatGoalSummaryLine(title, deadlineMillis, workingMillis, deadlineReached)` signature is identical in Task 1 (definition), Task 3 `CompactGoalRow` (call). `FocusCreationContent(progress, intention, lastClosure, onIntentionChange, onDurationChange, onStart)` is identical in Task 2 (definition + `FocusPanel` call) and Task 3 (sheet call). `CompactSheet { FOCUS, GOAL }` used consistently. `PomodoroStatus.IDLE` gating matches the enum in `Pomodoro.kt`. ✓
