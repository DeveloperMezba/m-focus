package com.focusguard.phone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Soft four-note chime (C-E-G-C) generated in code, played when a session ends. No audio files needed. */
object Chime {
    fun play() {
        Thread {
            runCatching {
                val rate = 44100
                val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
                val step = (rate * 0.42).toInt()
                val len = (rate * 1.9).toInt()
                val total = step * (notes.size - 1) + len
                val pcm = ShortArray(total)
                notes.forEachIndexed { i, f ->
                    val start = i * step
                    for (n in 0 until len) {
                        val idx = start + n
                        if (idx >= total) break
                        val t = n / rate.toDouble()
                        val env = exp(-2.6 * t) * min(1.0, t / 0.012)
                        val v = (sin(2 * PI * f * t) + 0.25 * sin(2 * PI * f * 2 * t)) * env * 0.22
                        pcm[idx] = (pcm[idx] + v * 32767).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(total * 1000L / rate + 400)
                track.release()
            }
        }.start()
    }
}
