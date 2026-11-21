package com.focusguard.phone

import android.content.Context

/** Tiny diagnostics: lets the app tell you WHY protection stopped (needed on phones we can't test). */
object Diag {
    private fun p(c: Context) = c.getSharedPreferences("fg", Context.MODE_PRIVATE)

    fun logCrash(c: Context, t: Throwable) {
        val text = System.currentTimeMillis().toString() + " " + t.javaClass.simpleName + ": " + t.message +
            "\n" + t.stackTrace.take(5).joinToString("\n")
        p(c).edit().putString("last_crash", text).commit()
    }

    fun lastCrash(c: Context): String? = p(c).getString("last_crash", null)

    /** The accessibility service went away while a session was running. */
    fun noteStop(c: Context, why: String) {
        p(c).edit().putLong("stop_at", System.currentTimeMillis()).putString("stop_why", why)
            .putBoolean("stop_seen", false).commit()
    }

    fun lastStop(c: Context): Pair<Long, String>? {
        val t = p(c).getLong("stop_at", 0L)
        return if (t > 0) t to (p(c).getString("stop_why", "") ?: "") else null
    }

    fun unseenStop(c: Context): Pair<Long, String>? =
        if (p(c).getBoolean("stop_seen", true)) null else lastStop(c)

    fun markSeen(c: Context) {
        p(c).edit().putBoolean("stop_seen", true).apply()
    }
}
