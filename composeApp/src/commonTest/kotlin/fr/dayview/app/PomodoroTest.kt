package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PomodoroTest {
    @Test
    fun idleSlotUsesTwentyFiveMinutesByDefault() {
        val progress = calculatePomodoroProgress(1_000L, 25, null)

        assertEquals(PomodoroStatus.IDLE, progress.status)
        assertEquals(25, progress.durationMinutes)
        assertEquals(1f, progress.remainingRatio)
        assertEquals("25:00", formatPomodoroClock(progress))
    }

    @Test
    fun activeSlotCountsDownFromPersistedDeadline() {
        val progress = calculatePomodoroProgress(
            nowMillis = 10 * 60_000L,
            durationMinutes = 25,
            endMillis = 25 * 60_000L,
        )

        assertEquals(PomodoroStatus.ACTIVE, progress.status)
        assertEquals(15 * 60_000L, progress.remainingMillis)
        assertTrue(progress.remainingRatio in .59f..61f)
        assertEquals("15:00", formatPomodoroClock(progress))
    }

    @Test
    fun exactDeadlineAndPastDeadlineAreFinished() {
        assertEquals(PomodoroStatus.FINISHED, calculatePomodoroProgress(1_000L, 25, 1_000L).status)
        assertEquals(PomodoroStatus.FINISHED, calculatePomodoroProgress(2_000L, 25, 1_000L).status)
    }

    @Test
    fun durationIsClampedToSupportedBounds() {
        assertEquals(5, calculatePomodoroProgress(0, -10, null).durationMinutes)
        assertEquals(180, calculatePomodoroProgress(0, 999, null).durationMinutes)
    }

    @Test
    fun futureDeadlineCannotExposeMoreThanTheCommittedDuration() {
        val progress = calculatePomodoroProgress(0, 25, 10_000_000L)

        assertEquals(25 * 60_000L, progress.remainingMillis)
        assertEquals(1f, progress.remainingRatio)
    }

    @Test
    fun compactMenuBarValueRoundsPartialMinuteUp() {
        val progress = calculatePomodoroProgress(
            nowMillis = 0,
            durationMinutes = 25,
            endMillis = 22 * 60_000L + 1,
        )

        assertEquals("23m", formatPomodoroCompactMinutes(progress))
        assertEquals("22m", formatPomodoroCompactMinutes(progress.copy(remainingMillis = 22 * 60_000L)))
    }
}
