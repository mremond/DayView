package fr.dayview.app

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

actual fun createSoundCuePlayer(): SoundCuePlayer = DesktopSoundCuePlayer()

private class DesktopSoundCuePlayer : SoundCuePlayer {
    private val sounds = mutableMapOf<SoundCue, SoundCuePcm>()

    @Volatile private var currentLine: SourceDataLine? = null

    @Volatile private var closed = false

    override fun play(cue: SoundCue, volume: Float) {
        if (closed) return
        val sound = synchronized(sounds) { sounds.getOrPut(cue) { synthesizeSoundCue(cue) } }
        Thread({
            if (closed) return@Thread
            val format = AudioFormat(sound.sampleRate.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            currentLine?.runCatching { stop() }
            currentLine?.close()
            currentLine = line
            line.open(format)
            line.start()
            val scaled = scalePcm(sound.bytes, volume.coerceIn(0f, 1f))
            line.write(scaled, 0, scaled.size)
            line.drain()
            if (currentLine === line) currentLine = null
            line.stop()
            line.close()
        }, "dayview-sound-cue").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        closed = true
        currentLine?.runCatching { stop() }
        currentLine?.close()
        currentLine = null
    }
}

private fun scalePcm(bytes: ByteArray, volume: Float): ByteArray {
    if (volume >= .999f) return bytes
    val scaled = bytes.copyOf()
    var index = 0
    while (index < scaled.size - 1) {
        val sample = ((scaled[index + 1].toInt() shl 8) or (scaled[index].toInt() and 0xff)).toShort()
        val value = (sample * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        scaled[index] = (value and 0xff).toByte()
        scaled[index + 1] = ((value ushr 8) and 0xff).toByte()
        index += 2
    }
    return scaled
}
