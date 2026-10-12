package com.example.innercircle

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * All game sounds are generated in code, so there are no audio files to manage.
 * One background thread mixes a continuous hum plus one-shot click and crash sounds.
 */
class SoundEngine {

    private val sampleRate = 44100
    private val twoPi = 2.0 * PI

    // ---- Hum controls, set by the game every frame ----
    @Volatile var humFreq = 70f      // Hz; the game raises this as the ring shrinks
    @Volatile var humVolume = 0f     // 0..1
    @Volatile var wobble = 0f        // 0..1, how wobbly the ring is (adds vibrato)
    @Volatile var danger = 0f        // 0..1, how close the circles are (adds a pulsing tremolo)

    @Volatile private var clickPending = false
    @Volatile private var crashPending = false
    @Volatile private var running = false
    private var thread: Thread? = null

    fun click() { clickPending = true }
    fun crash() { crashPending = true }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "InnerCircleAudio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (ignored: InterruptedException) { }
        thread = null
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }
        track.play()

        val buf = ShortArray(256)
        val rnd = java.util.Random()

        var freq = humFreq.toDouble()
        var vol = 0.0
        var phase = 0.0
        var vibPhase = 0.0
        var tremPhase = 0.0

        val clickLen = (sampleRate * 0.035).toInt()
        val crashLen = (sampleRate * 1.0).toInt()
        var clickPos = -1
        var crashPos = -1
        var crashLp = 0.0
        var thumpPhase = 0.0

        while (running) {
            if (clickPending) { clickPending = false; clickPos = 0 }
            if (crashPending) { crashPending = false; crashPos = 0; crashLp = 0.0; thumpPhase = 0.0 }

            val targetF = humFreq.toDouble()
            val targetV = humVolume.toDouble()
            val wb = wobble.toDouble()
            val dg = danger.toDouble()

            for (i in buf.indices) {
                // Smoothly glide toward the targets so changes never click.
                freq += (targetF - freq) * 0.0005
                vol += (targetV - vol) * 0.0008

                // ---- Hum ----
                // Phone speakers can't play deep bass, so the hum is built from several
                // harmonics; your ear still hears the low note underneath.
                vibPhase += twoPi * 5.0 / sampleRate
                val f = freq * (1.0 + 0.035 * wb * sin(vibPhase))
                phase += twoPi * f / sampleRate
                if (phase > twoPi) phase -= twoPi
                var hum = sin(phase) + 0.5 * sin(2 * phase) + 0.33 * sin(3 * phase) +
                    0.2 * sin(4 * phase) + 0.12 * sin(5 * phase)
                hum /= 2.15
                tremPhase += twoPi * (3.0 + 12.0 * dg) / sampleRate
                if (tremPhase > twoPi) tremPhase -= twoPi
                val trem = 1.0 - 0.5 * dg * (0.5 + 0.5 * sin(tremPhase))
                var s = hum * vol * trem

                // ---- Start click ----
                if (clickPos in 0 until clickLen) {
                    val t = clickPos.toDouble() / sampleRate
                    val env = exp(-t * 160.0)
                    s += 0.7 * env * (0.7 * sin(twoPi * 1700.0 * t) + 0.3 * (rnd.nextDouble() * 2 - 1))
                    clickPos++
                } else clickPos = -1

                // ---- Crash ----
                if (crashPos in 0 until crashLen) {
                    val t = crashPos.toDouble() / sampleRate
                    // Noise that darkens as it fades, plus a low thump that drops in pitch.
                    val cutoff = 0.6 * exp(-t * 3.0) + 0.03
                    crashLp += cutoff * ((rnd.nextDouble() * 2 - 1) - crashLp)
                    val thumpF = 90.0 * exp(-t * 3.0) + 30.0
                    thumpPhase += twoPi * thumpF / sampleRate
                    val env = exp(-t * 4.0)
                    s += env * (1.3 * crashLp + 0.8 * sin(thumpPhase) + 0.4 * sin(2 * thumpPhase))
                    crashPos++
                } else crashPos = -1

                if (s > 1.0) s = 1.0 else if (s < -1.0) s = -1.0
                buf[i] = (s * 30000).toInt().toShort()
            }
            track.write(buf, 0, buf.size)
        }

        try {
            track.stop()
        } catch (ignored: IllegalStateException) { }
        track.release()
    }
}
