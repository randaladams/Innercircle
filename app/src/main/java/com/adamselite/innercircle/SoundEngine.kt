package com.adamselite.innercircle

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
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
    @Volatile var brightness = 0f    // 0..1, how buzzy the hum is (set from the ring's shape)

    // ---- One-shot volumes ----
    var crashVolume = 0.22           // peak loudness of the crash (the hum plays at about 0.35)
    var failVolume = 0.22            // peak loudness of the missed-par sound

    @Volatile private var clickPending = false
    @Volatile private var crashPending = false
    @Volatile private var failPending = false
    @Volatile private var running = false
    private var thread: Thread? = null

    fun click() { clickPending = true }
    fun crash() { crashPending = true }
    fun fail() { failPending = true }     // missed par: sad "wah-wah-waaah"

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

    /** Quick fade-in, then a gentle fade-out over the note's length. */
    private fun noteEnv(t: Double, len: Double): Double {
        if (t < 0 || t > len) return 0.0
        val attack = min(1.0, t / 0.015)
        val release = min(1.0, (len - t) / 0.06)
        return attack * release * (1.0 - 0.35 * t / len)
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
        val crashLen = (sampleRate * 0.7).toInt()
        var clickPos = -1
        var crashPos = -1
        var popLp = 0.0
        var failPos = Int.MIN_VALUE
        var failPhase = 0.0
        val failDelay = (sampleRate * 0.45).toInt()   // starts just after the crash
        val failLen = (sampleRate * 1.6).toInt()
        var glidePhase = 0.0

        while (running) {
            if (clickPending) { clickPending = false; clickPos = 0 }
            if (failPending) { failPending = false; failPos = -failDelay; failPhase = 0.0 }
            if (crashPending) { crashPending = false; crashPos = 0; popLp = 0.0; glidePhase = 0.0 }

            val targetF = humFreq.toDouble()
            val targetV = humVolume.toDouble()
            val wb = wobble.toDouble()
            val dg = danger.toDouble()
            val br = brightness.toDouble()

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
                // Sharper shapes get stronger upper harmonics, so they sound buzzier.
                var hum = sin(phase) + 0.5 * sin(2 * phase) + (0.33 + 0.4 * br) * sin(3 * phase) +
                    (0.2 + 0.4 * br) * sin(4 * phase) + (0.12 + 0.4 * br) * sin(5 * phase)
                hum /= (2.15 + 1.2 * br)
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
                    // A soft "pop" followed by a falling "bwoww", like a bubble bursting
                    // and deflating. No harsh noise, so it never gets loud.
                    popLp += 0.25 * ((rnd.nextDouble() * 2 - 1) - popLp)
                    val pop = popLp * exp(-t * 220.0)
                    val glideF = 520.0 * exp(-t * 4.0) + 90.0
                    glidePhase += twoPi * glideF / sampleRate
                    val attack = min(1.0, t / 0.006)
                    val glide = attack * exp(-t * 5.5) * (sin(glidePhase) + 0.3 * sin(2 * glidePhase)) / 1.3
                    s += crashVolume * (0.8 * pop + glide)
                    crashPos++
                } else crashPos = -1

                // ---- Missed par: three falling notes, the last one sagging with a wobble ----
                if (failPos != Int.MIN_VALUE) {
                    if (failPos >= 0 && failPos < failLen) {
                        val t = failPos.toDouble() / sampleRate
                        var f = 0.0
                        var env = 0.0
                        when {
                            t < 0.26 -> { f = 311.0; env = noteEnv(t, 0.26) }
                            t < 0.52 -> { f = 294.0; env = noteEnv(t - 0.26, 0.26) }
                            else -> {
                                val u = t - 0.52
                                f = (277.0 - 40.0 * min(1.0, u / 0.9)) * (1.0 + 0.025 * sin(twoPi * 6.0 * u))
                                env = noteEnv(u, 1.05)
                            }
                        }
                        failPhase += twoPi * f / sampleRate
                        if (failPhase > twoPi) failPhase -= twoPi
                        // Muted brass-like tone: a few odd and even harmonics
                        val tone = (sin(failPhase) + 0.45 * sin(2 * failPhase) + 0.25 * sin(3 * failPhase) +
                            0.12 * sin(4 * failPhase)) / 1.82
                        s += failVolume * env * tone
                    }
                    failPos++
                    if (failPos >= failLen) failPos = Int.MIN_VALUE
                }

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
