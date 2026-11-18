package com.focusguard.phone

import android.content.Context
import java.time.LocalDate

/** Session state lives in SharedPreferences so it survives app kills and reboots. */
object Session {
    private fun p(c: Context) = c.getSharedPreferences("fg", Context.MODE_PRIVATE)

    fun endAt(c: Context): Long = p(c).getLong("end", 0L)
    fun totalMin(c: Context): Int = p(c).getInt("minutes", 0)
    fun isActive(c: Context): Boolean = endAt(c) > System.currentTimeMillis()
    fun remainingMs(c: Context): Long = (endAt(c) - System.currentTimeMillis()).coerceAtLeast(0)

    fun start(c: Context, requested: Int) {
        val minutes = requested.coerceIn(1, 480)
        p(c).edit()
            .putLong("end", System.currentTimeMillis() + minutes * 60_000L)
            .putInt("minutes", minutes).apply()
    }

    /** Call when the timer has run out. Returns true only the first time it really completes. */
    fun complete(c: Context): Boolean {
        val e = endAt(c)
        if (e == 0L || e > System.currentTimeMillis()) return false
        val key = "done_" + LocalDate.now()
        p(c).edit().putInt(key, p(c).getInt(key, 0) + totalMin(c)).putLong("end", 0L).apply()
        return true
    }

    fun todayMinutes(c: Context): Int = p(c).getInt("done_" + LocalDate.now(), 0)

    /** Last few packages that triggered the lock screen (shown small on the home screen, for debugging). */
    fun logBlocked(c: Context, pkg: String) {
        val cur = blockedLog(c).filter { it != pkg }
        p(c).edit().putString("blocked_log", (listOf(pkg) + cur).take(5).joinToString(",")).apply()
    }

    fun blockedLog(c: Context): List<String> =
        (p(c).getString("blocked_log", "") ?: "").split(",").filter { it.isNotBlank() }
}
