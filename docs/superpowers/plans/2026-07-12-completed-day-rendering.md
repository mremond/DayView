# Completed-day rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the finished-day countdown ring from an empty "expired" dial into a calm, accomplished closure — a full mint ring at rest with a small focus + sessions recap.

**Architecture:** All changes live in `CountdownCircle` in `DayViewTodayScreen.kt` (plus one test-tag constant and tests). No new state, no data-flow or public-API changes — `focusedToday`, `cleanSessionsToday`, `streakDays`, and the calendar/detour marks are already passed into the composable. The central recap is tag-testable; the Canvas ring/mark changes are pixel-only and are verified by build + lint + a manual desktop run, matching how the existing countdown tests only assert node tags, never Canvas content.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, `runComposeUiTest` (desktop), ktlint, Gradle.

## Global Constraints

- ktlint is enforced; run `./gradlew ktlintCheck` (or `ktlintFormat`) before each commit.
- Full gate before finishing: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — must pass with no errors or stderr.
- Compose UI tests must NOT assert `stringResource` text (unresolved under `runComposeUiTest` on CI). Assert via test tags and seeded data only. `assertExists`/`assertDoesNotExist` are members — no import needed.
- Commit messages describe the change only. NEVER add a "Generated with Claude Code" footer, a `Co-Authored-By: Claude` trailer, or any reference to Claude/Anthropic/an AI assistant. Do not reference documents under `docs/superpowers/`.
- Default day window in tests is 08:00–18:00 (`DayPreferencesSnapshot` defaults `startMinutes = 8*60`, `endMinutes = 18*60`).
- JDK 21 toolchain.

---

### Task 1: Central recap survives day end (focus line + calm title)

Today the focus recap line (`focused_today`) is locked inside `if (!progress.isFinished)` at `DayViewTodayScreen.kt:1010`, so it vanishes when the day ends. The clean-sessions block at `:1031` already renders in the finished state. This task makes the focus line render in the finished state too and softens the title colour from alarm red to mint. Add a test tag so the finished-state focus recap is assertable.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` (add `FocusRecap` constant)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (center `Column` inside `CountdownCircle`, ~lines 969–1069, and the title `Text` at ~line 970)
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt` (add `afterWindowNow()` helper)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/CompletedDayTest.kt` (new)

**Interfaces:**
- Consumes: `CountdownCircle(progress: DayProgress, showSeconds: Boolean, focusedToday: Duration = Duration.ZERO, cleanSessionsToday: Int = 0, streakDays: Int = 0, ...)` (existing internal composable); `calculateDayProgress(now, startMinutesOfDay, endMinutesOfDay)`; `DayViewTheme { colors -> ... }`; `formatDurationHm(Duration)`; `Res.string.focused_today`.
- Produces: `DayViewTestTags.FocusRecap = "focusRecap"` — a tag on the finished-state focus recap `Text`, consumed by tests only.

- [ ] **Step 1: Add the test-tag constant**

In `DayViewTestTags.kt`, add after the `CleanSessions` line (`:13`):

```kotlin
    const val FocusRecap = "focusRecap"
```

- [ ] **Step 2: Add an `afterWindowNow()` test helper**

In `UiTestSupport.kt`, right after the existing `midWindowNow()` function, add a sibling that lands after the default 18:00 day end so `DayProgress.isFinished` is true:

```kotlin
/**
 * A "now" past the default 08:00–18:00 day window (19:00 local), so
 * [calculateDayProgress] reports the day as finished regardless of timezone.
 */
internal fun afterWindowNow(): Instant {
    val tz = TimeZone.currentSystemDefault()
    val localNow = Clock.System.now().toLocalDateTime(tz)
    return LocalDateTime(
        year = localNow.year,
        month = localNow.month,
        day = localNow.day,
        hour = 19,
        minute = 0,
    ).toInstant(tz)
}
```

- [ ] **Step 3: Write the failing test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/CompletedDayTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class CompletedDayTest {
    private fun finishedProgress(): DayProgress =
        calculateDayProgress(
            now = afterWindowNow(),
            startMinutesOfDay = 8 * 60,
            endMinutesOfDay = 18 * 60,
        )

    @Test
    fun rendersFocusRecapWhenFinishedWithFocusTime() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    focusedToday = 90.minutes,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusRecap).assertExists()
    }

    @Test
    fun hidesFocusRecapWhenFinishedWithoutFocusTime() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    focusedToday = kotlin.time.Duration.ZERO,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.FocusRecap).assertDoesNotExist()
    }

    @Test
    fun rendersCleanSessionsWhenFinished() = runComposeUiTest {
        setContent {
            DayViewTheme {
                CountdownCircle(
                    progress = finishedProgress(),
                    showSeconds = false,
                    cleanSessionsToday = 3,
                    streakDays = 5,
                )
            }
        }
        onNodeWithTag(DayViewTestTags.CleanSessions).assertExists()
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests 'fr.dayview.app.CompletedDayTest'`
Expected: FAIL — `rendersFocusRecapWhenFinishedWithFocusTime` fails because no node has the `focusRecap` tag (the focus line is hidden in the finished state). (`rendersCleanSessionsWhenFinished` may already pass.)

- [ ] **Step 5: Add the finished-state focus recap and soften the title**

In `DayViewTodayScreen.kt`, update the title `Text` (currently at ~`:970`) so the finished colour is mint instead of red:

```kotlin
                        Text(
                            if (progress.isFinished) stringResource(Res.string.countdown_day_over) else stringResource(Res.string.countdown_time_left),
                            color = if (progress.isFinished) colors.mint else colors.muted,
                            fontSize = (11 * counterScale).sp,
                            lineHeight = (15 * counterScale).sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (1.8f * counterScale).sp,
                            textAlign = TextAlign.Center,
                        )
```

Then, immediately after the closing brace of the `if (!progress.isFinished) { ... }` block (currently at ~`:1030`, just before the `if (cleanSessionsToday > 0 || streakDays > 0)` block at `:1031`), add an `else` branch that shows the focus recap when the day is finished:

```kotlin
                        } else if (focusedToday > Duration.ZERO) {
                            Spacer(Modifier.height(8.dp * counterScale))
                            Text(
                                stringResource(Res.string.focused_today, formatDurationHm(focusedToday)),
                                color = colors.mint,
                                fontSize = (13 * counterScale).sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = (.5f * counterScale).sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag(DayViewTestTags.FocusRecap),
                            )
                        }
```

Note: this attaches to the existing `if (!progress.isFinished) {` so it reads `} else if (focusedToday > Duration.ZERO) {`. The shared clean-sessions block that follows stays unchanged and continues to render in both states.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests 'fr.dayview.app.CompletedDayTest'`
Expected: PASS (all three tests).

- [ ] **Step 7: Run lint**

Run: `./gradlew ktlintCheck`
Expected: PASS, no stderr. If it complains, run `./gradlew ktlintFormat` and re-run.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/CompletedDayTest.kt
git commit -m "Show focus and session recap on the finished-day ring"
```

---

### Task 2: The ring at rest

Today the whole arc-drawing block sits inside `if (animatedRemaining > 0f)` (`DayViewTodayScreen.kt:845`), so when the day is over (`animatedRemaining == 0`) nothing is drawn but the faint ghost track. Draw a full mint ring plus a resting marker at the top, and switch the finished accent from red to mint. This is a Canvas-only change with no node-tree signal, so it is verified by build + lint + a manual desktop run (the existing countdown tests never assert Canvas content either).

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (the `accent` `animateColorAsState` at ~`:736`, and the ring-drawing block at ~`:845`)

**Interfaces:**
- Consumes: `accent: Color` (existing local); `progress.isFinished`, `animatedRemaining`, `inset`, `arcSize`, `strokeWidth`, `size`, `colors.mint` (all in scope in the Canvas block).
- Produces: nothing consumed by other tasks.

- [ ] **Step 1: Switch the finished accent colour from red to mint**

In `CountdownCircle`, update the `accent` `animateColorAsState` (~`:736`):

```kotlin
    val accent by animateColorAsState(
        when {
            progress.isFinished -> colors.mint
            progress.remainingRatio < .2f -> colors.amber
            else -> colors.mint
        },
        label = "accent",
    )
```

- [ ] **Step 2: Draw the ring at rest when the day is finished**

The current block starts at `if (animatedRemaining > 0f) {` (~`:845`) and encloses the sweep/plain arc and the live amber moment marker, closing at ~`:905`. Add an `else` branch after that closing brace that draws the finished ring. Insert immediately after the `}` that closes `if (animatedRemaining > 0f)`:

```kotlin
                    } else if (progress.isFinished) {
                        // Day complete: the ring comes to rest as a full, calm mint circle
                        // (uniform colour — no leading edge to justify a sweep gradient), with a
                        // small resting marker parked at the top where the day began and ended.
                        drawArc(
                            color = accent.copy(alpha = .45f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth, cap = StrokeCap.Round),
                        )
                        val restCenter = Offset(size.width / 2f, inset)
                        drawCircle(
                            color = accent.copy(alpha = .22f),
                            radius = strokeWidth * .6f,
                            center = restCenter,
                        )
                        drawCircle(
                            color = accent,
                            radius = strokeWidth * .34f,
                            center = restCenter,
                        )
                    }
```

Note: the top of the ring is at angle −90°, whose point on the arc is horizontally centered (`size.width / 2f`) at vertical `inset` (the arc's top edge). `accent` is mint in the finished state after Step 1.

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run lint**

Run: `./gradlew ktlintCheck`
Expected: PASS. If it complains, `./gradlew ktlintFormat` then re-run.

- [ ] **Step 5: Manual visual verification**

Run: `./gradlew :composeApp:run`

To reach the finished state without waiting for the real end of day, temporarily set the day-end time in Settings to a minute before the current time (then restore it after checking). Confirm:
- The ring is a full, soft mint circle (not the faint grey ghost track).
- A small mint dot rests at the top of the ring.
- The centre title "JOURNÉE TERMINÉE" is mint, not red.
- The focus / sessions recap (from Task 1) shows under the title.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Draw the finished-day ring at rest in calm mint"
```

---

### Task 3: Fade the day's residual marks

When the day is over, the leftover calendar busy arcs (`DayViewTodayScreen.kt:914`) and detour bodies (`:936`) still draw at full intensity and compete with the mint ring and recap. Dim them to ~40% so they read as faint traces of a closed day. Canvas-only; verified by build + lint + manual run.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (busy-arc draw at ~`:914`, detour-body draw at ~`:936`)

**Interfaces:**
- Consumes: `progress.isFinished` (in scope in the Canvas block); existing `busyBlockArcs` / `detourBodies` draw code.
- Produces: nothing consumed by other tasks.

- [ ] **Step 1: Introduce a fade factor for the finished state**

In the Canvas block, just before the busy-arc `busyBlockArcs.forEach { ... }` loop (~`:914`), add:

```kotlin
                    val residualAlpha = if (progress.isFinished) .4f else 1f
```

- [ ] **Step 2: Apply the fade to the busy arcs**

In the `busyBlockArcs.forEach` loop, multiply both passes' alphas by `residualAlpha`:

```kotlin
                        drawArc(
                            color = col.copy(alpha = .16f * residualAlpha),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(busyInset, busyInset),
                            size = busyLaneSize,
                            style = Stroke(strokeWidth * .7f, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = col.copy(alpha = .92f * residualAlpha),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(busyInset, busyInset),
                            size = busyLaneSize,
                            style = Stroke(strokeWidth * .42f, cap = StrokeCap.Round),
                        )
```

- [ ] **Step 3: Apply the fade to the detour bodies**

In the `detourBodies.forEach` loop (~`:936`), multiply each `drawCircle` alpha by `residualAlpha`. The color glow, core, and highlight become:

```kotlin
                        drawCircle(color = color.copy(alpha = .28f * residualAlpha), radius = radius * 1.5f, center = bodyCenter)
                        drawCircle(color = color.copy(alpha = residualAlpha), radius = radius, center = bodyCenter)
                        drawCircle(
                            color = Color.White.copy(alpha = .5f * residualAlpha),
                            radius = radius * .28f,
                            center = bodyCenter - Offset(radius * .3f, radius * .3f),
                        )
```

Note: the core circle was previously `drawCircle(color = color, radius = radius, ...)` with the color at full opacity; wrapping it in `.copy(alpha = residualAlpha)` leaves it unchanged (alpha 1f) during the day and fades it only when finished.

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run lint**

Run: `./gradlew ktlintCheck`
Expected: PASS. If it complains, `./gradlew ktlintFormat` then re-run.

- [ ] **Step 6: Manual visual verification**

Run: `./gradlew :composeApp:run` and reach the finished state (temporarily set day-end before now, as in Task 2). With at least one calendar event or detour on the ring, confirm the busy pills and detour dots appear as faint traces (clearly dimmer than during the day), and the mint ring + recap remain the visual focus. Restore the day-end time afterward.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Fade calendar and detour marks on the finished-day ring"
```

---

### Final gate

- [ ] **Run the full test + lint gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS with no errors or stderr.

- [ ] **Confirm the branch log shows only the three intended commits**

Run: `git log origin/main..HEAD --oneline`
Expected: the three commits from Tasks 1–3 (plus the design/plan doc commits already present on the branch).
