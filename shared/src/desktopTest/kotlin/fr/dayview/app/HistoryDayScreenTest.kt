package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HistoryDayScreenTest {
    private val record = DayHistoryRecord(
        dayKey = 20_000L, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(),
        netTimeSettings = NetTimeSettings(), focusPresenceIntervals = emptyList(),
        focusSessionIntervals = emptyList(),
        focusSessionRecords = emptyList(),
        detours = emptyList(), cleanSessions = CleanSessionLedger(dayKey = 20_000L),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun rendersTheRingForAPastDay() = runComposeUiTest {
        setContent { DayViewTheme { HistoryDayScreen(record = record, onBack = {}) } }
        // The ring composable is present (tag defined on CountdownCircle's root Box).
        onNodeWithTag(DayViewTestTags.Countdown).assertExists()
    }

    @Test
    fun titleShowsTheViewedDayDate() = runComposeUiTest {
        setContent { DayViewTheme { HistoryDayScreen(record = record, onBack = {}) } }
        // Content (weekday + localized date) is guarded by LocalizedStringsTest; resource
        // text is unresolved under runComposeUiTest on CI, so only presence is asserted.
        onNodeWithTag(DayViewTestTags.HistoryDayTitle).assertExists()
    }

    @Test
    fun backControlInvokesCallback() = runComposeUiTest {
        var backed = false
        setContent { DayViewTheme { HistoryDayScreen(record = record, onBack = { backed = true }) } }
        onNodeWithTag(DayViewTestTags.HistoryBack).performClick()
        assertTrue(backed)
    }
}
