package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue
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

    @Test
    fun rendersCleanSessionsLineWhenPresent() = runComposeUiTest {
        val now = midWindowNow()
        val dayKey = dayKeyOf(now)
        val snapshot = DayPreferencesSnapshot(
            cleanSessions = CleanSessionLedger(dayKey = dayKey, cleanToday = 3, streakDays = 5, streakLastDayKey = dayKey),
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.CleanSessions).assertExists()
    }

    @Test
    fun rendersLiveStreakBeforeFirstSessionOfDay() = runComposeUiTest {
        val now = midWindowNow()
        val dayKey = dayKeyOf(now)
        val snapshot = DayPreferencesSnapshot(
            cleanSessions = CleanSessionLedger(
                dayKey = dayKey,
                cleanToday = 0,
                streakDays = 5,
                streakLastDayKey = dayKey - 1,
            ),
        )
        setContent {
            val state = remember { seededController(snapshot, now).state }
            WideDayView(state = state, actions = noopDayViewActions())
        }
        onNodeWithTag(DayViewTestTags.CleanSessions).assertExists()
    }
}
