package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
}
