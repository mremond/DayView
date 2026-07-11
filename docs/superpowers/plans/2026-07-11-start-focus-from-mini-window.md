# Start Focus From Mini Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the desktop mini window start focus (via an intention modal) and stop an active focus session.

**Architecture:** The mini window (`DayViewMiniApp`, commonMain) becomes interactive with two new callbacks. When idle it shows a "Start focus" button that opens an in-window intention modal (scrim + card); when active it shows a Stop control. The desktop `Main.kt` wires those callbacks to the existing `preferences.savePomodoro` / `saveFocusIntention` methods, and the already-running `preferences.observe` loop propagates changes back to every surface. A small pure helper computes the pomodoro end time and is unit-tested.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (desktop target), JUnit (`kotlin.test`) for the desktop unit test.

## Global Constraints

- ktlint is enforced — run `./gradlew ktlintCheck` (or `ktlintFormat`) before every commit.
- Pomodoro duration is always coerced into `5..180` minutes (matches `calculatePomodoroProgress`).
- Focus intention is capped at 100 characters (matches `DayViewController.setFocusIntention`).
- UI copy is in French, matching the surrounding app (e.g. `DÉMARRER LE FOCUS`, `Une seule chose à la fois`).
- Verification command for the whole plan: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.

---

### Task 1: `focusStartEndMillis` helper

Pure function that computes a pomodoro end time from "now" and a duration, coercing the duration to the valid range. Mirrors `DayViewController.startPomodoro()` math and the Android tile's start path, but lives where the desktop mini-window wiring can call it and be unit-tested.

**Files:**
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/FocusStart.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusStartTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `fun focusStartEndMillis(nowMillis: Long, durationMinutes: Int): Long` — returns `nowMillis + durationMinutes.coerceIn(5, 180) * 60_000L`. Consumed by `Main.kt` in Task 2.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusStartTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusStartTest {
    @Test
    fun addsDurationInMinutesToNow() {
        assertEquals(1_000L + 25 * 60_000L, focusStartEndMillis(1_000L, 25))
    }

    @Test
    fun coercesDurationBelowFiveMinutesUpToFive() {
        assertEquals(5 * 60_000L, focusStartEndMillis(0L, 1))
    }

    @Test
    fun coercesDurationAboveOneEightyMinutesDownToOneEighty() {
        assertEquals(180 * 60_000L, focusStartEndMillis(0L, 500))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FocusStartTest"`
Expected: FAIL — compilation error, `focusStartEndMillis` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/desktopMain/kotlin/fr/dayview/app/FocusStart.kt`:

```kotlin
package fr.dayview.app

/**
 * Computes the pomodoro end time for a focus session started at [nowMillis]
 * with the given [durationMinutes], coercing the duration into the valid
 * 5..180 minute range (matching calculatePomodoroProgress).
 */
fun focusStartEndMillis(nowMillis: Long, durationMinutes: Int): Long =
    nowMillis + durationMinutes.coerceIn(5, 180) * 60_000L
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FocusStartTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/FocusStart.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusStartTest.kt
git commit -m "feat: add focus-start end-time helper"
```

---

### Task 2: Start / stop focus in the mini window

Make `DayViewMiniApp` interactive: an idle "Start focus" button that opens an in-window intention modal, and a Stop control while focus is active. Wire the two new callbacks in `Main.kt`. The composable-signature change and its single call site land together so the build stays green.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewMiniApp.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (change two composables from `private` to `internal` for reuse)
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt:216-224` (call site)

**Interfaces:**
- Consumes: `focusStartEndMillis(nowMillis, durationMinutes)` from Task 1; the existing `internal fun CountdownCircle(...)`; `preferences.saveFocusIntention(String)` and `preferences.savePomodoro(Int, Long?)` from `DayPreferences`.
- Produces: updated `DayViewMiniApp` signature —
  ```kotlin
  fun DayViewMiniApp(
      progress: DayProgress,
      showSeconds: Boolean,
      nowMillis: Long,
      goalTitle: String,
      goalDeadlineMillis: Long?,
      pomodoro: PomodoroProgress,
      focusIntention: String,
      onStartFocus: (String) -> Unit,
      onStopFocus: () -> Unit,
  )
  ```

- [ ] **Step 1: Make the reused primitives `internal`**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`, change the two declarations (they are used by the modal in the mini window):

Line ~961: `private fun FocusActionButton(` → `internal fun FocusActionButton(`
Line ~1103: `private fun GoalTextField(` → `internal fun GoalTextField(`

- [ ] **Step 2: Rewrite `DayViewMiniApp.kt` with the interactive UI**

Replace the entire contents of `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewMiniApp.kt` with:

```kotlin
package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DayViewMiniApp(
    progress: DayProgress,
    showSeconds: Boolean,
    nowMillis: Long,
    goalTitle: String,
    goalDeadlineMillis: Long?,
    pomodoro: PomodoroProgress,
    focusIntention: String,
    onStartFocus: (String) -> Unit,
    onStopFocus: () -> Unit,
) {
    DayViewTheme { colors ->
        var showIntentionModal by remember { mutableStateOf(false) }
        var draftIntention by remember(focusIntention) { mutableStateOf(focusIntention) }

        Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(colors.glow, colors.ink),
                                radius = 650f,
                            ),
                        )
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CountdownCircle(
                        progress = progress,
                        showSeconds = showSeconds,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    MiniGoal(
                        title = goalTitle,
                        deadlineMillis = goalDeadlineMillis,
                        nowMillis = nowMillis,
                        workStartMinutes = progress.startHour * 60 + progress.startMinute,
                        workEndMinutes = progress.endHour * 60 + progress.endMinute,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (pomodoro.status == PomodoroStatus.IDLE) {
                        MiniFocusStart(
                            onClick = {
                                draftIntention = focusIntention
                                showIntentionModal = true
                            },
                        )
                    } else {
                        MiniFocus(
                            progress = pomodoro,
                            intention = focusIntention,
                            onStop = onStopFocus,
                        )
                    }
                }
                if (showIntentionModal) {
                    FocusIntentionModal(
                        intention = draftIntention,
                        onIntentionChange = { draftIntention = it },
                        onStart = {
                            onStartFocus(draftIntention)
                            showIntentionModal = false
                        },
                        onDismiss = { showIntentionModal = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniGoal(
    title: String,
    deadlineMillis: Long?,
    nowMillis: Long,
    workStartMinutes: Int,
    workEndMinutes: Int,
) {
    val colors = LocalDayViewColors.current
    val remaining = deadlineMillis?.let {
        formatGoalWorkingHours(
            workingMillis = calculateGoalWorkingMillis(
                nowMillis = nowMillis,
                deadlineMillis = it,
                startMinutesOfDay = workStartMinutes,
                endMinutesOfDay = workEndMinutes,
            ),
            deadlineReached = it <= nowMillis,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(15.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(15.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "OBJECTIF GLOBAL",
                color = colors.mint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                title.ifBlank { "Aucun objectif défini" },
                color = if (title.isBlank()) colors.muted else colors.cloud,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        remaining?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, color = colors.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun MiniFocusStart(onClick: () -> Unit) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.amber.copy(alpha = .1f), RoundedCornerShape(15.dp))
            .border(1.dp, colors.amber.copy(alpha = .25f), RoundedCornerShape(15.dp))
            .clickable(role = Role.Button, onClickLabel = "Démarrer un focus", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "DÉMARRER UN FOCUS",
                color = colors.amber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Une seule chose à la fois",
                color = colors.muted,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("+", color = colors.amber, fontSize = 26.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun MiniFocus(
    progress: PomodoroProgress,
    intention: String,
    onStop: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val isBreak = progress.status == PomodoroStatus.BREAK
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.amber.copy(alpha = .1f), RoundedCornerShape(15.dp))
            .border(1.dp, colors.amber.copy(alpha = .25f), RoundedCornerShape(15.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (isBreak) "PAUSE EN COURS" else "FOCUS EN COURS",
                color = if (isBreak) colors.mint else colors.amber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                intention.ifBlank { "Une seule chose à la fois" },
                color = colors.cloud,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (isBreak) formatBreakClock(progress) else formatPomodoroClock(progress),
            color = colors.cloud,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.width(12.dp))
        MiniStopButton(onStop)
    }
}

@Composable
private fun MiniStopButton(onStop: () -> Unit) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = Modifier.size(40.dp)
            .background(colors.overlay.copy(alpha = .08f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Arrêter le focus", onClick = onStop),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(12.dp)
                .background(colors.red.copy(alpha = .85f), RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun FocusIntentionModal(
    intention: String,
    onIntentionChange: (String) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = Modifier.fillMaxSize()
            .background(colors.ink.copy(alpha = .6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .08f), RoundedCornerShape(18.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(18.dp),
        ) {
            Text(
                "FOCUS",
                color = colors.amber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "À LA FIN DE CE FOCUS, J’AURAI…",
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(8.dp))
            GoalTextField(
                value = intention,
                semanticLabel = "Intention du Focus",
                placeholder = "Ex. terminé le plan de la présentation",
                onValueChange = onIntentionChange,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FocusActionButton(
                    "ANNULER",
                    colors.muted,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                FocusActionButton(
                    "DÉMARRER",
                    colors.amber,
                    modifier = Modifier.weight(1f),
                    enabled = intention.isNotBlank(),
                    filled = true,
                    onClick = onStart,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Wire the callbacks in `Main.kt`**

In `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt`, replace the `DayViewMiniApp(...)` call (currently lines ~216-224) with:

```kotlin
            DayViewMiniApp(
                progress = dayProgress,
                showSeconds = showSeconds,
                nowMillis = nowMillis,
                goalTitle = goalTitle,
                goalDeadlineMillis = goalDeadline,
                pomodoro = pomodoro,
                focusIntention = focusIntention,
                onStartFocus = { intention ->
                    preferences.saveFocusIntention(intention.trim().take(100))
                    preferences.savePomodoro(
                        pomodoroMinutes,
                        focusStartEndMillis(nowMillis, pomodoroMinutes),
                    )
                },
                onStopFocus = { preferences.savePomodoro(pomodoroMinutes, null) },
            )
```

- [ ] **Step 4: Verify ktlint and the full test suite pass**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint violations, all tests pass.

- [ ] **Step 5: Manually verify the flow in the running app**

Run: `./gradlew :composeApp:run`
Then, in the app:
1. Open the menu-bar tray → "Afficher la mini-fenêtre".
2. Confirm the idle mini window shows the "DÉMARRER UN FOCUS" button.
3. Click it → the intention modal appears, pre-filled with any saved intention.
4. With the field blank, confirm "DÉMARRER" is disabled; type an intention → it enables.
5. Click "DÉMARRER" → the modal closes and the mini window shows "FOCUS EN COURS" with the countdown and a stop button.
6. Click the stop button → focus ends and the "DÉMARRER UN FOCUS" button returns.
7. Confirm the tray focus line and, on macOS, the menu-bar minutes label reflect the same state.

Expected: all steps behave as described.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewMiniApp.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt
git commit -m "feat: start and stop focus from the desktop mini window"
```

---

## Self-Review

**Spec coverage:**
- Idle "Start focus" button → `MiniFocusStart` (Task 2). ✓
- Intention modal, always opens, pre-filled, Start disabled when blank → `FocusIntentionModal` + `draftIntention` seeding + `enabled = intention.isNotBlank()` (Task 2). ✓
- Start persists trimmed ≤100-char intention + starts pomodoro of saved duration → `Main.kt` `onStartFocus` using `focusStartEndMillis` (Tasks 1–2). ✓
- Active/Break Stop button → `MiniStopButton` in `MiniFocus` + `onStopFocus` (Task 2). ✓
- Menu bar unchanged, no duration control → neither touched. ✓
- Pure start helper covered by a `desktopTest` → Task 1. ✓
- Wiring reuses `savePomodoro` / `saveFocusIntention` + existing `observe` loop → `Main.kt` (Task 2), loop untouched. ✓

**Placeholder scan:** No TBD/TODO; all code blocks are complete.

**Type consistency:** `focusStartEndMillis(nowMillis: Long, durationMinutes: Int): Long` is defined in Task 1 and called with `(nowMillis, pomodoroMinutes)` in Task 2. `DayViewMiniApp`'s new `onStartFocus: (String) -> Unit` / `onStopFocus: () -> Unit` match the call site. `FocusActionButton` and `GoalTextField` signatures used in the modal match their definitions in `DayViewTodayScreen.kt` (made `internal` in Task 2, Step 1).
