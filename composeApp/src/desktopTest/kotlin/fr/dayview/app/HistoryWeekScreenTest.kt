package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HistoryWeekScreenTest {
    private fun record(dayKey: Long) = DayHistoryRecord(
        dayKey = dayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
        focusPresenceIntervals = emptyList(), detours = emptyList(), cleanSessions = CleanSessionLedger(),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun daysWithDataAreClickableAndGapsAreNot() = runComposeUiTest {
        var clicked: Long? = null
        val days = listOf(
            HistoryWeekDay(10L, "Mon", record(10L)),
            HistoryWeekDay(11L, "Tue", null), // gap
        )
        setContent {
            DayViewTheme {
                HistoryWeekScreen(days = days, onSelectDay = { clicked = it }, onBack = {})
            }
        }

        onNodeWithTag(DayViewTestTags.historyDayCell(10L)).assertHasClickAction().performClick()
        assertEquals(10L, clicked)
        onNodeWithTag(DayViewTestTags.historyDayCell(11L)).assertHasNoClickAction()
    }
}
