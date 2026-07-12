# Mini ⇄ Main window glyphs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the text "MINI" header button with a picture-in-picture glyph, and give the mini window an always-visible expand glyph that returns to the main window.

**Architecture:** Two hand-drawn `Canvas` glyph composables in a new shared `WindowGlyphs.kt` (matching how the app already draws its `+` and stop-square instead of using Material Icons). The main-window `Header` and `DayViewMiniApp` each wrap a glyph in a clickable, tagged, min-touch-target `Box`. A new `onOpenMainWindow` callback on `DayViewMiniApp` is wired in `Main.kt` to flip the window-visibility flags, mirroring the existing tray "Open full window" action.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, `runComposeUiTest` (desktopTest).

## Global Constraints

- ktlint is enforced. Run `./gradlew ktlintCheck` (or `ktlintFormat`) before committing.
- Full pre-commit gate: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Compose UI tests assert via `testTag`, **never** via `stringResource` text (unresolved under `runComposeUiTest` on CI). `assertExists` is a member — no import needed.
- No new user-facing strings: reuse `mini_window_button` and `desktop_open_full_window` as accessibility `onClickLabel`s.
- No reference to Claude/Anthropic/AI in commit messages.

---

### Task 1: Main-window mini button — PiP glyph

Replace the "MINI" text in the header with a picture-in-picture glyph button. Creates the shared glyph file (with the one glyph this task needs) and the test tag.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/WindowGlyphs.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`Header`, ~line 572-606)
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt` (`noopDayViewActions`, ~line 66)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/TodayScreenTest.kt`

**Interfaces:**
- Produces: `MiniWindowGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp)` in `WindowGlyphs.kt`; `DayViewTestTags.MiniWindow = "miniWindowButton"`; `noopDayViewActions(openMiniWindow = ...)` override.

- [ ] **Step 1: Add the test tag**

In `DayViewTestTags.kt`, add inside the object (after `Countdown`):

```kotlin
    const val MiniWindow = "miniWindowButton"
```

- [ ] **Step 2: Let the test helper supply a non-null `openMiniWindow`**

The header only renders the mini button when `openMiniWindow != null`, but `noopDayViewActions` hard-codes it to `null`. In `UiTestSupport.kt`, add an override param and use it. Change the `noopDayViewActions` signature and body:

```kotlin
internal fun noopDayViewActions(
    openSettings: () -> Unit = {},
    openMiniWindow: (() -> Unit)? = null,
    changeFocusIntention: (String) -> Unit = {},
    changePomodoroDuration: (Int) -> Unit = {},
    startPomodoro: () -> Unit = {},
    stopPomodoro: () -> Unit = {},
    closePomodoro: (FocusClosureOutcome) -> Unit = {},
): DayViewScreenActions = DayViewScreenActions(
    openSettings = openSettings,
    openMiniWindow = openMiniWindow,
    changeGoalTitle = {},
    changeGoalDeadline = {},
    commitGoalDeadline = {},
    changeGoalStart = {},
    commitGoalStart = {},
    changeFocusIntention = changeFocusIntention,
    changePomodoroDuration = changePomodoroDuration,
    startPomodoro = startPomodoro,
    stopPomodoro = stopPomodoro,
    closePomodoro = closePomodoro,
)
```

- [ ] **Step 3: Write the failing test**

Add to `TodayScreenTest.kt`. Add imports `import androidx.compose.ui.test.performClick` and `import kotlin.test.assertTrue` at the top with the other imports.

```kotlin
    @Test
    fun miniWindowButtonInvokesCallback() = runComposeUiTest {
        var miniOpened = false
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot()).state }
            WideDayView(
                state = state,
                actions = noopDayViewActions(openMiniWindow = { miniOpened = true }),
            )
        }
        onNodeWithTag(DayViewTestTags.MiniWindow).assertExists()
        onNodeWithTag(DayViewTestTags.MiniWindow).performClick()
        assertTrue(miniOpened)
    }
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.TodayScreenTest.miniWindowButtonInvokesCallback"`
Expected: FAIL — `MiniWindowGlyph` unresolved / tag `miniWindowButton` not found.

- [ ] **Step 5: Create the shared glyph file with `MiniWindowGlyph`**

Create `WindowGlyphs.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small window-mode glyphs, hand-drawn with Canvas to match the app's other
 * inline glyphs (the focus-start "+", the mini stop square) rather than pulling
 * in a Material icon dependency. Shared by the main-window header and the mini
 * window. Coordinates map the approved 20-unit design onto the drawn size.
 */

/**
 * Picture-in-picture: an outer window frame with a small filled pane in the
 * bottom-right corner. Reads "shrink to mini window".
 */
@Composable
fun MiniWindowGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.075f
        val inset = stroke / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.11f + inset, s * 0.175f + inset),
            size = Size(s * 0.78f - stroke, s * 0.65f - stroke),
            cornerRadius = CornerRadius(s * 0.11f, s * 0.11f),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.50f, s * 0.48f),
            size = Size(s * 0.30f, s * 0.22f),
            cornerRadius = CornerRadius(s * 0.05f, s * 0.05f),
        )
    }
}
```

Note: `ExpandWindowGlyph` (Task 2) will need three more imports (`Path`, `StrokeCap`, `StrokeJoin`); they are intentionally **not** added here so this task passes ktlint's `no-unused-imports` rule. Task 2 adds them.

- [ ] **Step 6: Replace the "MINI" text in `Header` with the glyph button**

In `DayViewTodayScreen.kt`, the `Header` composable currently has this block:

```kotlin
        onOpenMiniWindow?.let {
            Text(
                stringResource(Res.string.mini_window_button),
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClick = it)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            )
            Spacer(Modifier.width(18.dp))
        }
```

Replace it with:

```kotlin
        onOpenMiniWindow?.let {
            Box(
                modifier = Modifier
                    .testTag(DayViewTestTags.MiniWindow)
                    .minimumInteractiveComponentSize()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(Res.string.mini_window_button),
                        onClick = it,
                    )
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                MiniWindowGlyph(color = colors.muted)
            }
            Spacer(Modifier.width(18.dp))
        }
```

`Box`, `Alignment`, `testTag`, `minimumInteractiveComponentSize`, `clickable`, `Role`, and `stringResource` are already imported in this file (used elsewhere). The `mini_window_button` `Res.string` import at the top stays — it is now the accessibility label. `FontWeight`, `sp`, and the `Text` import may become unused after this edit **only if** no other code in the file uses them; they are used elsewhere in this large file, so leave the imports.

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.TodayScreenTest.miniWindowButtonInvokesCallback"`
Expected: PASS.

- [ ] **Step 8: Run lint + the existing Today tests for regressions**

Run: `./gradlew ktlintCheck :composeApp:desktopTest --tests "fr.dayview.app.TodayScreenTest"`
Expected: BUILD SUCCESSFUL, all Today tests green.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/WindowGlyphs.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/TodayScreenTest.kt
git commit -m "Replace MINI header text with a picture-in-picture glyph button"
```

---

### Task 2: Mini window — expand glyph returns to main window

Add the `ExpandWindowGlyph`, an always-visible corner button on the mini window, a new `onOpenMainWindow` callback, and its wiring in `Main.kt`.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/WindowGlyphs.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewMiniApp.kt`
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt` (mini `DayViewMiniApp(...)` call, ~line 312-338)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/MiniWindowTest.kt` (new)

**Interfaces:**
- Consumes: `MiniWindowGlyph` conventions from Task 1; `DayViewTestTags` object.
- Produces: `ExpandWindowGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp)`; `DayViewTestTags.OpenMainWindow = "openMainWindowButton"`; `DayViewMiniApp(..., onOpenMainWindow: () -> Unit, ...)`.

- [ ] **Step 1: Add the test tag**

In `DayViewTestTags.kt`, add:

```kotlin
    const val OpenMainWindow = "openMainWindowButton"
```

- [ ] **Step 2: Write the failing test**

Create `MiniWindowTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MiniWindowTest {
    @Test
    fun openMainWindowButtonInvokesCallback() = runComposeUiTest {
        var mainOpened = false
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, null),
                focusIntention = "",
                onStartFocus = {},
                onStopFocus = {},
                onOpenMainWindow = { mainOpened = true },
            )
        }
        onNodeWithTag(DayViewTestTags.OpenMainWindow).assertExists()
        onNodeWithTag(DayViewTestTags.OpenMainWindow).performClick()
        assertTrue(mainOpened)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.MiniWindowTest"`
Expected: FAIL — `onOpenMainWindow` is not a parameter of `DayViewMiniApp` / `ExpandWindowGlyph` unresolved / tag not found.

- [ ] **Step 4: Add `ExpandWindowGlyph` to `WindowGlyphs.kt`**

First add the three imports it needs, alongside the existing graphics imports:

```kotlin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
```

Then append this composable to `WindowGlyphs.kt`:

```kotlin
/**
 * Two diagonal corner arrows pushing outward (top-right and bottom-left).
 * Reads "grow back to the full window".
 */
@Composable
fun ExpandWindowGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.075f
        val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val topRight = Path().apply {
            moveTo(s * 0.55f, s * 0.15f)
            lineTo(s * 0.85f, s * 0.15f)
            lineTo(s * 0.85f, s * 0.45f)
        }
        drawPath(topRight, color, style = line)
        drawLine(
            color = color,
            start = Offset(s * 0.85f, s * 0.15f),
            end = Offset(s * 0.55f, s * 0.45f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val bottomLeft = Path().apply {
            moveTo(s * 0.45f, s * 0.85f)
            lineTo(s * 0.15f, s * 0.85f)
            lineTo(s * 0.15f, s * 0.55f)
        }
        drawPath(bottomLeft, color, style = line)
        drawLine(
            color = color,
            start = Offset(s * 0.15f, s * 0.85f),
            end = Offset(s * 0.45f, s * 0.55f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
```

- [ ] **Step 5: Add the `onOpenMainWindow` parameter and corner button to `DayViewMiniApp`**

In `DayViewMiniApp.kt`:

First, add imports near the other imports:

```kotlin
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.platform.testTag
import fr.dayview.app.generated.resources.desktop_open_full_window
```

(`androidx.compose.foundation.layout.padding` is already imported — do not duplicate it; add only the ones missing.)

Add the parameter to the function signature (after `onStopFocus`):

```kotlin
    onStartFocus: (String) -> Unit,
    onStopFocus: () -> Unit,
    onOpenMainWindow: () -> Unit,
) {
```

Then, inside the outer `Box(Modifier.fillMaxSize()) { ... }`, add the button as a sibling of the `Column` and the modal (place it after the `Column { ... }` block, before the `if (showIntentionModal)` block):

```kotlin
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .testTag(DayViewTestTags.OpenMainWindow)
                        .minimumInteractiveComponentSize()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(Res.string.desktop_open_full_window),
                            onClick = onOpenMainWindow,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    ExpandWindowGlyph(color = colors.muted)
                }
```

`colors` is in scope from the enclosing `DayViewTheme { colors -> ... }`. `Alignment`, `clickable`, `Role`, `stringResource`, `Box` are already imported.

- [ ] **Step 6: Wire `onOpenMainWindow` in `Main.kt`**

In `Main.kt`, the `DayViewMiniApp(...)` call (~line 312) passes `progress`, `showSeconds`, …, `onStartFocus`, `onStopFocus`. Add the new argument (e.g. right after `onStopFocus = { ... },`):

```kotlin
                onOpenMainWindow = {
                    isMiniWindowVisible = false
                    isWindowVisible = true
                },
```

This mirrors the tray "Open full window" item, which flips the same two flags.

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.MiniWindowTest"`
Expected: PASS.

- [ ] **Step 8: Full pre-commit gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint violations, all tests green.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/WindowGlyphs.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewMiniApp.kt \
        composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/MiniWindowTest.kt
git commit -m "Add expand glyph to mini window to return to the main window"
```

---

### Task 3: Visual verification

The glyph proportions were tuned blind from the approved SVG mock; confirm they render cleanly at real size.

- [ ] **Step 1: Launch the desktop app**

Run: `./gradlew :composeApp:run`

- [ ] **Step 2: Verify the main-window glyph**

In the header, confirm a picture-in-picture glyph sits where "MINI" used to be, in the muted color, before "SETTINGS". Click it → the mini window opens and the main window hides.

- [ ] **Step 3: Verify the mini-window glyph**

In the mini window's top-right corner, confirm an always-visible expand-arrows glyph. Click it → the mini window closes and the main window returns.

- [ ] **Step 4: Tune if needed**

If either glyph looks off (clipping, weight, alignment), adjust the fraction constants in `WindowGlyphs.kt` and re-run. Re-run `./gradlew ktlintCheck :composeApp:desktopTest` after any change, then amend the relevant commit or add a follow-up commit `Tune window glyph proportions`.

---

## Notes for the implementer

- The two glyph buttons deliberately have **no** background/border — they match the bare, text-like affordances already in the header (SETTINGS) and keep the mini window uncluttered.
- Do not add new strings; the two reused strings become accessibility labels only.
- If ktlint reorders imports, run `./gradlew ktlintFormat` and re-stage.
