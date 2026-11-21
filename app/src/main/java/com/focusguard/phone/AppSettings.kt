package com.focusguard.phone

import android.content.Context
import android.os.Build
import android.os.SystemClock
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

    fun tick(c: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - last < 22) return
        last = now
        val haptic = AppSettings.haptic(c)
        if (AppSettings.click(c)) TickSound.play(if (haptic) 0.08f else 0.18f)
        if (haptic) vibrate(c)
    }

    private fun vibrate(c: Context) {
        runCatching {
            val v = c.getSystemService(Vibrator::class.java)
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(18)
            }
        }
    }
}
