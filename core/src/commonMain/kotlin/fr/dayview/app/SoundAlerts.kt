package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

enum class SoundCue {
    DAY_START,
    INTERVAL,
    DAY_END,
    BREAK_REMINDER,
}

data class SoundSettings(
    val enabled: Boolean = false,
    val startCueEnabled: Boolean = true,
    val intervalCueEnabled: Boolean = true,
    val endCueEnabled: Boolean = true,
    val intervalMinutes: Int = 30,
    val volumePercent: Int = 40,
) {
    fun normalized(): SoundSettings = copy(
        intervalMinutes = snapIntervalMinutes(intervalMinutes),
        volumePercent = volumePercent.coerceIn(10, 100),
    )

    fun allows(cue: SoundCue): Boolean = enabled &&
        when (cue) {
            SoundCue.DAY_START -> startCueEnabled
            SoundCue.INTERVAL -> intervalCueEnabled
            SoundCue.DAY_END -> endCueEnabled
            SoundCue.BREAK_REMINDER -> true
        }

    fun allowsDayCue(cue: SoundCue, focusIsActive: Boolean): Boolean = !focusIsActive && allows(cue)

    companion object {
        /** Selectable minute values for the out-of-focus reminder interval, ascending. */
        val INTERVAL_CHOICES: List<Int> = listOf(5, 10, 15, 20, 25, 30, 60)

        /** Snaps an arbitrary minute value to the nearest choice; ties resolve to the smaller value. */
        fun snapIntervalMinutes(minutes: Int): Int = INTERVAL_CHOICES.minByOrNull { abs(it - minutes) } ?: 30
    }
}

class SoundAlertScheduler(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private var previous: Instant? = null

    fun observe(
        now: Instant,
        startMinutesOfDay: Int,
        endMinutesOfDay: Int,
        intervalMinutes: Int,
    ): SoundCue? {
        val previousObservation = previous
        previous = now
        if (previousObservation == null || now <= previousObservation) return null

        val localNow = now.toLocalDateTime(timeZone)
        val startMinutes = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
        val endMinutes = endMinutesOfDay.coerceIn(startMinutes + 30, 23 * 60 + 59)
        val interval = SoundSettings.snapIntervalMinutes(intervalMinutes)
        fun at(minutes: Int): Instant = LocalDateTime(
            year = localNow.year,
            month = localNow.month,
            day = localNow.day,
            hour = minutes / 60,
            minute = minutes % 60,
        ).toInstant(timeZone)

        val endInstant = at(endMinutes)
        if (crossedRecently(previousObservation, now, endInstant)) return SoundCue.DAY_END

        val startInstant = at(startMinutes)
        if (crossedRecently(previousObservation, now, startInstant)) return SoundCue.DAY_START

        var markerMinutes = startMinutes + interval
        var latestMarker: Instant? = null
        while (markerMinutes < endMinutes) {
            val markerInstant = at(markerMinutes)
            if (crossedRecently(previousObservation, now, markerInstant)) latestMarker = markerInstant
            markerMinutes += interval
        }
        return if (latestMarker != null) SoundCue.INTERVAL else null
    }

    private fun crossedRecently(previous: Instant, now: Instant, threshold: Instant): Boolean = previous < threshold && now >= threshold && now - threshold <= MAX_ALERT_LATENESS

    private companion object {
        val MAX_ALERT_LATENESS = 90.seconds
    }
}
