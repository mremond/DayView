package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class UpcomingDaysStateTest {
    // Anchor to local midnight, then +12h, so "finished" for a 00:00-00:30 window holds in any zone.
    private val now = startOfLocalDay(Instant.fromEpochMilliseconds(1_700_000_000_000L)) + 12.hours

    private fun controller(scope: CoroutineScope, enabled: Boolean, endMinutes: Int) = DayViewController(
        DefaultDayPreferences,
        scope,
        initialSnapshot = DayPreferencesSnapshot(
            startMinutes = 0,
            endMinutes = endMinutes,
            netTimeSettings = NetTimeSettings(enabled = enabled),
        ),
        initialNow = now,
    )

    private fun tomorrowStart(): Instant {
        val fromDate = kotlinx.datetime.LocalDate.fromEpochDays((dayKeyOf(now) + 1).toInt())
        return dayWindowFor(fromDate, 0, 30).first
    }

    @Test
    fun emptyWhenNetTimeDisabledEvenIfFinishedAndFed() = runTest {
        val controller = controller(backgroundScope, enabled = false, endMinutes = 30)
        controller.updateUpcomingData(dayKeyOf(now) + 1, emptyList())
        assertEquals(emptyList(), controller.state.upcomingDays)
    }

    @Test
    fun emptyWhenNotFinished() = runTest {
        // Full-day window: 12:00 local is mid-window, not finished.
        val controller = controller(backgroundScope, enabled = true, endMinutes = 23 * 60 + 59)
        controller.updateUpcomingData(dayKeyOf(now) + 1, emptyList())
        assertEquals(emptyList(), controller.state.upcomingDays)
    }

    @Test
    fun emptyWhenNoDataFed() = runTest {
        val controller = controller(backgroundScope, enabled = true, endMinutes = 30)
        assertTrue(controller.state.dayProgress.isFinished)
        assertEquals(emptyList(), controller.state.upcomingDays)
    }

    @Test
    fun populatedWhenFinishedEnabledAndFed() = runTest {
        val controller = controller(backgroundScope, enabled = true, endMinutes = 30)
        val busy = BusyInterval(tomorrowStart() + 5.minutes, tomorrowStart() + 20.minutes)
        controller.updateUpcomingData(dayKeyOf(now) + 1, listOf(busy))
        val days = controller.state.upcomingDays
        assertEquals(UPCOMING_DAY_COUNT, days.size)
        // 30-min window minus a 15-min meeting -> 15 min net tomorrow.
        assertEquals(15.minutes, days[0].net)
    }

    @Test
    fun focusBlocksAreExcludedFromBusy() = runTest {
        val controller = controller(backgroundScope, enabled = true, endMinutes = 30)
        val focus = BusyInterval(tomorrowStart() + 5.minutes, tomorrowStart() + 20.minutes, listOf("Focus"))
        controller.updateUpcomingData(dayKeyOf(now) + 1, listOf(focus))
        // Focus-titled event excluded -> full 30-min window available.
        assertEquals(30.minutes, controller.state.upcomingDays[0].net)
    }

    @Test
    fun staleFromDayKeyReadsAsEmpty() = runTest {
        val controller = controller(backgroundScope, enabled = true, endMinutes = 30)
        controller.updateUpcomingData(dayKeyOf(now) + 5, emptyList()) // wrong day
        assertEquals(emptyList(), controller.state.upcomingDays)
    }
}
