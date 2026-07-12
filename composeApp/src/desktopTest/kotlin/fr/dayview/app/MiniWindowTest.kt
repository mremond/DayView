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
