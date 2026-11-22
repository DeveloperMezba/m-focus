package com.focusguard.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Very short, soft click (like a reel ratchet) played for every minute the dial moves. Built in code. */
object TickSound {
    private var pool: SoundPool? = null
    private var soundId = 0
    private var loaded = false
    private var last = 0L

    fun init(ctx: Context) {
        if (pool != null) return
        runCatching {
            val f = File(ctx.cacheDir, "tick.wav")
            if (!f.exists()) f.writeBytes(wav())
            val sp = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            sp.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
            soundId = sp.load(f.path, 1)
            pool = sp
        }
    }

    fun isLoaded(): Boolean = loaded

    fun play(volume: Float = 0.2f) {
        val now = SystemClock.uptimeMillis()
        if (!loaded || now - last < 20) return   // fast spinning still gives a ratchet, not a buzz
        last = now
        runCatching { pool?.play(soundId, volume, volume, 1, 0, 1f) }
    }

    private fun wav(): ByteArray {
        val rate = 22050
        val n = (rate * 0.035).toInt()
        val data = ByteArray(n * 2)
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val t = i / rate.toDouble()
            val env = exp(-t * 170.0)
            val v = (rnd.nextDouble() * 2 - 1) * 0.45 + sin(2 * PI * 2300 * t) * 0.55
            val s = (v * env * 0.9 * 32767).toInt().coerceIn(-32768, 32767)
            data[2 * i] = (s and 0xFF).toByte()
            data[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data.size).put(data)
            .array()
    }
}
