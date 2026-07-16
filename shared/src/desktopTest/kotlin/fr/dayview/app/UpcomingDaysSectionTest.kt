package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalTestApi::class)
class UpcomingDaysSectionTest {
    private fun day(dayOfMonth: Int, netHours: Long) = UpcomingDayAvailability(
        date = LocalDate(2026, 1, dayOfMonth),
        window = 10.hours,
        busy = (10 - netHours).hours,
        net = netHours.hours,
    )

    @Test
    fun rendersSectionWhenDaysPresent() = runComposeUiTest {
        setContent {
            DayViewTheme {
                UpcomingDaysSection(days = listOf(day(15, 7), day(16, 6), day(17, 8)))
            }
        }
        onNodeWithTag(DayViewTestTags.UpcomingDays).assertExists()
    }

    @Test
    fun rendersNothingWhenEmpty() = runComposeUiTest {
        setContent {
            DayViewTheme {
                UpcomingDaysSection(days = emptyList())
            }
        }
        onNodeWithTag(DayViewTestTags.UpcomingDays).assertDoesNotExist()
    }
}
