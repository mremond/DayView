package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.time.Instant

enum class SoundCue {
    DAY_START,
    INTERVAL,
    DAY_END,
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
        intervalMinutes = intervalMinutes.coerceIn(30, 180),
        volumePercent = volumePercent.coerceIn(10, 100),
    )

    fun allows(cue: SoundCue): Boolean = enabled && when (cue) {
        SoundCue.DAY_START -> startCueEnabled
        SoundCue.INTERVAL -> intervalCueEnabled
        SoundCue.DAY_END -> endCueEnabled
    }

    fun allowsDayCue(cue: SoundCue, focusIsActive: Boolean): Boolean =
        !focusIsActive && allows(cue)
}

class SoundAlertScheduler(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private var previousMillis: Long? = null

    fun observe(
        nowMillis: Long,
        startMinutesOfDay: Int,
        endMinutesOfDay: Int,
        intervalMinutes: Int,
    ): SoundCue? {
        val previous = previousMillis
        previousMillis = nowMillis
        if (previous == null || nowMillis <= previous) return null

        val localNow = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone)
        val startMinutes = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
        val endMinutes = endMinutesOfDay.coerceIn(startMinutes + 30, 23 * 60 + 59)
        val interval = intervalMinutes.coerceIn(30, 180)
        fun at(minutes: Int): Long = LocalDateTime(
            year = localNow.year,
            month = localNow.month,
            day = localNow.day,
            hour = minutes / 60,
            minute = minutes % 60,
        ).toInstant(timeZone).toEpochMilliseconds()

        val endMillis = at(endMinutes)
        if (crossedRecently(previous, nowMillis, endMillis)) return SoundCue.DAY_END

        val startMillis = at(startMinutes)
        if (crossedRecently(previous, nowMillis, startMillis)) return SoundCue.DAY_START

        var markerMinutes = startMinutes + interval
        var latestMarker: Long? = null
        while (markerMinutes < endMinutes) {
            val markerMillis = at(markerMinutes)
            if (crossedRecently(previous, nowMillis, markerMillis)) latestMarker = markerMillis
            markerMinutes += interval
        }
        return if (latestMarker != null) SoundCue.INTERVAL else null
    }

    private fun crossedRecently(previous: Long, now: Long, threshold: Long): Boolean =
        previous < threshold && now >= threshold && now - threshold <= MAX_ALERT_LATENESS_MILLIS

    private companion object {
        const val MAX_ALERT_LATENESS_MILLIS = 90_000L
    }
}

data class SoundCuePcm(val sampleRate: Int, val bytes: ByteArray)

fun synthesizeSoundCue(cue: SoundCue, sampleRate: Int = 44_100): SoundCuePcm {
    val durationSeconds = when (cue) {
        SoundCue.DAY_START -> 1.45
        SoundCue.INTERVAL -> 0.9
        SoundCue.DAY_END -> 2.4
    }
    val sampleCount = (sampleRate * durationSeconds).toInt()
    val bytes = ByteArray(sampleCount * 2)
    repeat(sampleCount) { index ->
        val t = index.toDouble() / sampleRate
        val attack = (t / 0.018).coerceIn(0.0, 1.0)
        val sample = when (cue) {
            SoundCue.DAY_START -> resonantTone(t, 523.25, 2.7) * 0.72 + resonantTone(t, 784.88, 3.4) * 0.28
            SoundCue.INTERVAL -> resonantTone(t, 659.25, 4.4) * 0.82 + resonantTone(t, 1318.5, 5.8) * 0.18
            SoundCue.DAY_END -> resonantTone(t, 196.0, 1.45) * 0.58 +
                resonantTone(t, 293.66, 1.9) * 0.28 + resonantTone(t, 437.0, 2.6) * 0.14
        }
        val value = (sample * attack * 0.42).coerceIn(-1.0, 1.0)
        val pcm = (value * Short.MAX_VALUE).toInt()
        bytes[index * 2] = (pcm and 0xff).toByte()
        bytes[index * 2 + 1] = ((pcm ushr 8) and 0xff).toByte()
    }
    return SoundCuePcm(sampleRate, bytes)
}

private fun resonantTone(time: Double, frequency: Double, decay: Double): Double {
    val fundamental = sin(2.0 * PI * frequency * time)
    val shimmer = sin(2.0 * PI * frequency * 2.01 * time) * 0.18
    return (fundamental + shimmer) * exp(-decay * time)
}

interface SoundCuePlayer {
    fun play(cue: SoundCue, volume: Float)
    fun close()
}

expect fun createSoundCuePlayer(): SoundCuePlayer
