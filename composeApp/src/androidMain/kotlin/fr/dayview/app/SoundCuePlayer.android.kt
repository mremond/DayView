package fr.dayview.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

actual fun createSoundCuePlayer(): SoundCuePlayer = AndroidSoundCuePlayer()

private class AndroidSoundCuePlayer : SoundCuePlayer {
    private val sounds = mutableMapOf<SoundCue, SoundCuePcm>()
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var closed = false

    override fun play(cue: SoundCue, volume: Float) {
        if (closed) return
        val sound = synchronized(sounds) { sounds.getOrPut(cue) { synthesizeSoundCue(cue) } }
        Thread({
            if (closed) return@Thread
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sound.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(sound.bytes.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            currentTrack?.runCatching { stop() }
            currentTrack?.release()
            currentTrack = track
            track.write(sound.bytes, 0, sound.bytes.size, AudioTrack.WRITE_BLOCKING)
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
            Thread.sleep(sound.bytes.size * 1_000L / 2 / sound.sampleRate + 80L)
            if (currentTrack === track) currentTrack = null
            track.runCatching { stop() }
            track.release()
        }, "dayview-sound-cue").start()
    }

    override fun close() {
        closed = true
        currentTrack?.runCatching { stop() }
        currentTrack?.release()
        currentTrack = null
    }
}
