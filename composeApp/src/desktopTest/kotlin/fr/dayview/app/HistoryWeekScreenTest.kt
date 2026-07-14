package fr.dayview.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HistoryWeekScreenTest {
    private fun record(dayKey: Long) = DayHistoryRecord(
        dayKey = dayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
        focusPresenceIntervals = emptyList(), focusSessionIntervals = emptyList(),
        detours = emptyList(), cleanSessions = CleanSessionLedger(),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun daysWithDataAreClickableAndGapsAreNot() = runComposeUiTest {
        var clicked: Long? = null
        val days = listOf(
            HistoryWeekDay(10L, record(10L)),
            HistoryWeekDay(11L, null), // gap
        )
        setContent {
            DayViewTheme {
                HistoryWeekScreen(days = days, onSelectDay = { clicked = it }, onBack = {})
            }
        }

        onNodeWithTag(DayViewTestTags.historyDayCell(10L))
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionContains("1970", substring = true)
            .assertContentDescriptionContains("Focus", substring = true, ignoreCase = true)
            .performClick()
        assertEquals(10L, clicked)
        onNodeWithTag(DayViewTestTags.historyDayCell(11L))
            .assertHasNoClickAction()
            .assertContentDescriptionContains("1970", substring = true)
            .assertContentDescriptionContains("histor", substring = true, ignoreCase = true)
        onNodeWithTag(DayViewTestTags.MiniRing, useUnmergedTree = true).assertWidthIsEqualTo(28.dp)
    }

    @Test
    fun descriptionIncludesFocusStatistics() = runComposeUiTest {
        val dayKey = 10L
        val date = LocalDate.fromEpochDays(dayKey.toInt())
        val timeZone = TimeZone.currentSystemDefault()
        val focusedRecord = record(dayKey).copy(
            focusPresenceIntervals = listOf(
                FocusPresenceInterval(
                    start = date.atTime(LocalTime(9, 0)).toInstant(timeZone),
                    end = date.atTime(LocalTime(10, 0)).toInstant(timeZone),
                ),
            ),
        )
        setContent {
            DayViewTheme {
                HistoryWeekScreen(
                    days = listOf(HistoryWeekDay(dayKey, focusedRecord)),
                    onSelectDay = {},
                    onBack = {},
                )
            }
        }

        onNodeWithTag(DayViewTestTags.historyDayCell(dayKey))
            .assertContentDescriptionContains("Focus", substring = true, ignoreCase = true)
            .assertContentDescriptionContains("1 h", substring = true)
    }

    @Test
    fun narrowHistoryWrapsWithoutShrinkingTargets() = runComposeUiTest {
        val days = (0L..6L).map { HistoryWeekDay(it, null) }
        setContent {
            DayViewTheme {
                Box(Modifier.requiredSize(360.dp, 720.dp)) {
                    HistoryWeekScreen(days = days, onSelectDay = {}, onBack = {})
                }
            }
        }

        val first = onNodeWithTag(DayViewTestTags.historyDayCell(0L))
            .assertWidthIsAtLeast(48.dp)
            .fetchSemanticsNode()
        val fifth = onNodeWithTag(DayViewTestTags.historyDayCell(4L))
            .assertWidthIsAtLeast(48.dp)
            .fetchSemanticsNode()
        assertTrue(fifth.boundsInRoot.top > first.boundsInRoot.top)
    }
}
