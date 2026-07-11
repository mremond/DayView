package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SoundAlertsTest {
    private val utc = TimeZone.UTC

    @Test
    fun firstObservationNeverProducesAnAlert() {
        val scheduler = SoundAlertScheduler(utc)

        assertNull(scheduler.observe(at(8, 0), 8 * 60, 18 * 60, 60))
    }

    @Test
    fun emitsStartIntervalAndEndCuesAtTheirBoundaries() {
        val scheduler = SoundAlertScheduler(utc)
        scheduler.observe(at(7, 59, 59), 8 * 60, 18 * 60, 60)
        assertEquals(SoundCue.DAY_START, scheduler.observe(at(8, 0), 8 * 60, 18 * 60, 60))

        scheduler.observe(at(8, 59, 59), 8 * 60, 18 * 60, 60)
        assertEquals(SoundCue.INTERVAL, scheduler.observe(at(9, 0), 8 * 60, 18 * 60, 60))

        scheduler.observe(at(17, 59, 59), 8 * 60, 18 * 60, 60)
        assertEquals(SoundCue.DAY_END, scheduler.observe(at(18, 0), 8 * 60, 18 * 60, 60))
    }

    @Test
    fun staleCrossingsDoNotRingAfterAResume() {
        val scheduler = SoundAlertScheduler(utc)
        scheduler.observe(at(7, 0), 8 * 60, 18 * 60, 60)

        assertNull(scheduler.observe(at(12, 10), 8 * 60, 18 * 60, 60))
    }

    @Test
    fun shortIntervalEmitsMarkerCues() {
        val scheduler = SoundAlertScheduler(utc)
        scheduler.observe(at(8, 4, 59), 8 * 60, 18 * 60, 5)

        assertEquals(SoundCue.INTERVAL, scheduler.observe(at(8, 5), 8 * 60, 18 * 60, 5))
    }

    @Test
    fun normalizationSnapsIntervalToNearestChoiceAndClampsVolume() {
        assertEquals(
            SoundSettings(intervalMinutes = 5, volumePercent = 100),
            SoundSettings(intervalMinutes = 5, volumePercent = 250).normalized(),
        )
        assertEquals(5, SoundSettings(intervalMinutes = 7).normalized().intervalMinutes)
        assertEquals(60, SoundSettings(intervalMinutes = 90).normalized().intervalMinutes)
        assertEquals(60, SoundSettings(intervalMinutes = 250).normalized().intervalMinutes)
        // 45 is equidistant from 30 and 60; ties resolve to the smaller choice.
        assertEquals(30, SoundSettings(intervalMinutes = 45).normalized().intervalMinutes)
    }

    @Test
    fun halfHourIsTheDefaultInterval() {
        assertEquals(30, SoundSettings().intervalMinutes)
    }

    @Test
    fun dayCuesAreSilentDuringAFocusSession() {
        val settings = SoundSettings(enabled = true)

        assertEquals(false, settings.allowsDayCue(SoundCue.INTERVAL, focusIsActive = true))
        assertEquals(true, settings.allowsDayCue(SoundCue.INTERVAL, focusIsActive = false))
    }

    private fun at(hour: Int, minute: Int, second: Int = 0): Instant = LocalDateTime(2026, 7, 11, hour, minute, second).toInstant(utc)
}
