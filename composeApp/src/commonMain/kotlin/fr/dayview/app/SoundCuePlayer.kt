package fr.dayview.app

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

data class SoundCuePcm(val sampleRate: Int, val bytes: ByteArray)

fun synthesizeSoundCue(cue: SoundCue, sampleRate: Int = 44_100): SoundCuePcm {
    val durationSeconds = when (cue) {
        SoundCue.DAY_START -> 1.45
        SoundCue.INTERVAL -> 0.9
        SoundCue.DAY_END -> 2.4
        SoundCue.BREAK_REMINDER -> 0.75
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
            SoundCue.BREAK_REMINDER -> resonantTone(t, 880.0, 5.2) * 0.78 +
                resonantTone(t, 1320.0, 6.4) * 0.22
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
