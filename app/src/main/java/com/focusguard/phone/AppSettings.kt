package com.focusguard.phone

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

object AppSettings {
    private fun p(c: Context) = c.getSharedPreferences("fg", Context.MODE_PRIVATE)

    fun haptic(c: Context): Boolean = p(c).getBoolean("set_haptic", true)
    fun click(c: Context): Boolean = p(c).getBoolean("set_click", true)
    fun theme(c: Context): Int = p(c).getInt("set_theme", 0).coerceIn(0, Palettes.lastIndex)

    fun setHaptic(c: Context, v: Boolean) = p(c).edit().putBoolean("set_haptic", v).apply()
    fun setClick(c: Context, v: Boolean) = p(c).edit().putBoolean("set_click", v).apply()
    fun setTheme(c: Context, v: Int) = p(c).edit().putInt("set_theme", v).apply()
}

/** What happens for every minute the dial moves. Haptic on = vibration + a softer click. */
object Feedback {
    private var last = 0L

    /** One step of the dial. */
    fun tick(c: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - last < 22) return
        last = now
        val haptic = AppSettings.haptic(c)
        if (AppSettings.click(c)) TickSound.play(if (haptic) 0.08f else 0.18f)
        if (haptic) vibrate(c, 18, false)
    }

    /** Last 5 seconds of a session: one firm buzz every second. */
    fun countdown(c: Context) {
        if (AppSettings.haptic(c)) vibrate(c, 90, true)
    }

    /** Settings > Feedback > Test */
    fun test(c: Context) {
        TickSound.play(0.3f)
        vibrate(c, 40, false)
    }

    fun status(c: Context): String {
        val v = c.getSystemService(Vibrator::class.java)
        val has = v?.hasVibrator() == true
        val amp = Build.VERSION.SDK_INT >= 26 && v?.hasAmplitudeControl() == true
        return "Motor: " + (if (has) "found, strength control " + (if (amp) "yes" else "no") else "not found") +
            "  |  Sound: " + (if (TickSound.isLoaded()) "ready" else "not ready")
    }

    /**
     * Newer Android versions drop vibrations that have no usage, or a "touch" usage when touch vibration is off.
     * So dial ticks use the ACCESSIBILITY usage and the countdown uses ALARM.
     */
    private fun vibrate(c: Context, ms: Long, alarm: Boolean) {
        runCatching {
            val v = c.getSystemService(Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return
            val fx = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 33) {
                val usage = if (alarm) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_ACCESSIBILITY
                v.vibrate(fx, VibrationAttributes.createForUsage(usage))
            } else {
                val usage = if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                @Suppress("DEPRECATION")
                v.vibrate(fx, AudioAttributes.Builder().setUsage(usage).build())
            }
        }
    }
}
