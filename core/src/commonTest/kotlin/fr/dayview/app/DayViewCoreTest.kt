package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DayViewCoreTest {
    @Test
    fun snapshotFlattensCalculateDayProgress() {
        val nowMillis = 1_700_000_000_000L
        val start = 540 // 09:00
        val end = 1080 // 18:00

        val snapshot = DayViewCore.dayProgress(nowMillis, start, end)
        val expected = calculateDayProgress(
            now = Instant.fromEpochMilliseconds(nowMillis),
            startMinutesOfDay = start,
            endMinutesOfDay = end,
        )

        assertEquals(expected.remaining.inWholeSeconds, snapshot.remainingSeconds)
        assertEquals(expected.remainingRatio.toDouble(), snapshot.remainingRatio)
        assertEquals(
            currentMomentAngleDegrees(expected.remainingRatio).toDouble(),
            snapshot.momentAngleDegrees,
        )
        assertEquals(expected.isFinished, snapshot.isFinished)
        assertEquals(expected.remainingHours, snapshot.remainingHours)
        assertEquals(expected.remainingMinutes, snapshot.remainingMinutes)
    }
}
