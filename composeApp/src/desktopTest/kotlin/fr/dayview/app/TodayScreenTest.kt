package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class TodayScreenTest {
    @Test
    fun rendersCountdownGoalAndFocusEntry() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(goalTitle = "Livrer la v2")
        setContent {
            val state = remember { seededController(snapshot).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.Countdown).assertExists()
        assertTextEventuallyExists("IL RESTE")
        onNodeWithText("Livrer la v2").assertExists()
        onNodeWithTag(DayViewTestTags.FocusStart).assertExists()
    }

    @Test
    fun rendersActiveFocusState() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            focusIntention = "Écrire le rapport",
            pomodoroEnd = now + 25.minutes,
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithText("Écrire le rapport").assertExists()
        onNodeWithTag(DayViewTestTags.FocusStop).assertExists()
    }
}
