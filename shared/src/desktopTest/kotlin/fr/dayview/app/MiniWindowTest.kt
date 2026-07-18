package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

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
                onCloseFocus = {},
                onOpenMainWindow = { mainOpened = true },
            )
        }
        onNodeWithTag(DayViewTestTags.OpenMainWindow).assertExists()
        onNodeWithTag(DayViewTestTags.OpenMainWindow).performClick()
        assertTrue(mainOpened)
    }

    @Test
    fun relaunchButtonDuringBreakStartsNextFocusWithSameIntention() = runComposeUiTest {
        var startedIntention: String? = null
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, now - 1.minutes),
                focusIntention = "Couper 5k secs",
                onStartFocus = { startedIntention = it },
                onStopFocus = {},
                onCloseFocus = {},
                onOpenMainWindow = {},
            )
        }
        onNodeWithTag(DayViewTestTags.MiniFocusRelaunch).assertExists()
        onNodeWithTag(DayViewTestTags.MiniFocusRelaunch).performClick()
        assertEquals("Couper 5k secs", startedIntention)
    }

    @Test
    fun relaunchButtonHiddenWhileFocusIsActive() = runComposeUiTest {
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, now + 10.minutes),
                focusIntention = "Couper 5k secs",
                onStartFocus = {},
                onStopFocus = {},
                onCloseFocus = {},
                onOpenMainWindow = {},
            )
        }
        onNodeWithTag(DayViewTestTags.MiniFocusRelaunch).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).assertDoesNotExist()
    }

    @Test
    fun closureButtonsDuringBreakReportTheOutcome() = runComposeUiTest {
        var closedWith: FocusClosureOutcome? = null
        val now = midWindowNow()
        setContent {
            DayViewMiniApp(
                progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                showSeconds = false,
                now = now,
                goalTitle = "",
                goalDeadline = null,
                pomodoro = calculatePomodoroProgress(now, 25, now - 1.minutes),
                focusIntention = "Couper 5k secs",
                onStartFocus = {},
                onStopFocus = {},
                onCloseFocus = { closedWith = it },
                onOpenMainWindow = {},
            )
        }
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.TO_RESUME)).assertExists()
        onNodeWithTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)).performClick()
        assertEquals(FocusClosureOutcome.COMPLETED, closedWith)
    }

    @Test
    fun minimumMiniWindowKeepsPrimaryControlsAndHidesGoalAtLargeText() = runComposeUiTest {
        val now = midWindowNow()
        setContent {
            VisualViewport(width = 200.dp, height = 300.dp) {
                DayViewMiniApp(
                    progress = calculateDayProgress(now, 8 * 60, 18 * 60),
                    showSeconds = false,
                    now = now,
                    goalTitle = "Livrer la version du jour",
                    goalDeadline = null,
                    pomodoro = calculatePomodoroProgress(now, 25, null),
                    focusIntention = "",
                    fontScale = 1.5f,
                    onStartFocus = {},
                    onStopFocus = {},
                    onCloseFocus = {},
                    onOpenMainWindow = {},
                )
            }
        }

        captureVisual("mini-window-minimum-200x300-font150")
        onNodeWithTag(DayViewTestTags.MiniGoal).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.OpenMainWindow).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.MiniFocusStart).assertIsDisplayed().assertInsideVisualViewport(this)
    }

    @Test
    fun minimumMiniWindowKeepsFocusFormActionsVisible() = runComposeUiTest {
        val now = midWindowNow()
        setContent {
            VisualViewport(width = 200.dp, height = 300.dp) {
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
                    onCloseFocus = {},
                    onOpenMainWindow = {},
                )
            }
        }

        onNodeWithTag(DayViewTestTags.MiniFocusStart).performClick()
        captureVisual("mini-window-focus-form-minimum-200x300")
        onNodeWithTag(DayViewTestTags.MiniFocusModal).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.MiniFocusConfirm).assertIsDisplayed().assertInsideVisualViewport(this)
        onNodeWithTag(DayViewTestTags.MiniFocusCancel).assertIsDisplayed().assertInsideVisualViewport(this)
    }
}
