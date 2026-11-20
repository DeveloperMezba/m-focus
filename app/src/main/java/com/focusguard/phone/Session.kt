package com.focusguard.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where the session is right now. untilChangeMs = time to the next break (focus) or to its end (break); -1 = none. */
data class PhaseInfo(val onBreak: Boolean, val untilChangeMs: Long)

data class DayStat(val date: LocalDate, val focusMin: Int)

data class Report(
    val days: List<DayStat>,          // last 7 days, oldest first
    val weekFocus: Int,
    val weekSessions: Int,
    val streak: Int,
    val todayFocus: Int,
    val attempts: List<Pair<String, Int>>,   // package -> tries this week, most first
    val attemptsTotal: Int,
)

/** Session state lives in SharedPreferences so it survives app kills and reboots. */
object Session {
    private const val MIN_STREAK_DAY = 10      // minutes of focus for a day to count in the streak
    private const val MIN_FOCUS_AFTER_BREAK = 5 // no break in the last minutes of a session

    private fun p(c: Context) = c.getSharedPreferences("fg", Context.MODE_PRIVATE)

    // ---------- current session ----------
    fun endAt(c: Context): Long = p(c).getLong("end", 0L)
    fun startAt(c: Context): Long = p(c).getLong("start", 0L)
    fun totalMin(c: Context): Int = p(c).getInt("minutes", 0)
    fun breakEvery(c: Context): Int = p(c).getInt("break_every", 0)
    fun breakLen(c: Context): Int = p(c).getInt("break_len", 0)
    fun isActive(c: Context): Boolean = endAt(c) > System.currentTimeMillis()
    fun remainingMs(c: Context): Long = (endAt(c) - System.currentTimeMillis()).coerceAtLeast(0)

    /** breakEvery <= 0 means no breaks. */
    fun start(c: Context, requested: Int, breakEvery: Int, breakLen: Int) {
        val minutes = requested.coerceIn(1, 480)
        val now = System.currentTimeMillis()
        p(c).edit()
            .putLong("start", now)
            .putLong("end", now + minutes * 60_000L)
            .putInt("minutes", minutes)
            .putInt("break_every", if (hasBreak(minutes, breakEvery, breakLen)) breakEvery else 0)
            .putInt("break_len", breakLen)
            .putString("attempts", "{}")
            .apply()
    }

    fun hasBreak(total: Int, every: Int, len: Int): Boolean =
        every > 0 && len > 0 && every + len + MIN_FOCUS_AFTER_BREAK <= total

    /** Focus minutes of a session = total minus the breaks that really happen. */
    fun focusMinutes(total: Int, every: Int, len: Int): Int {
        if (!hasBreak(total, every, len)) return total
        var breaks = 0
        var k = 0
        while ((k + 1) * (every + len) + MIN_FOCUS_AFTER_BREAK <= total) {
            breaks += len
            k++
        }
        return total - breaks
    }

    fun phase(c: Context): PhaseInfo {
        val e = breakEvery(c)
        val b = breakLen(c)
        val start = startAt(c)
        val total = totalMin(c)
        if (e <= 0 || b <= 0 || start == 0L) return PhaseInfo(false, -1)
        val now = System.currentTimeMillis()
        val cycle = (e + b) * 60_000L
        val k = (now - start) / cycle
        val breakStart = start + k * cycle + e * 60_000L
        val breakEnd = breakStart + b * 60_000L
        val valid = (breakEnd - start) + MIN_FOCUS_AFTER_BREAK * 60_000L <= total * 60_000L
        if (valid && now >= breakStart && now < breakEnd) return PhaseInfo(true, breakEnd - now)
        if (valid && now < breakStart) return PhaseInfo(false, breakStart - now)
        val nStart = start + (k + 1) * cycle + e * 60_000L
        val nEnd = nStart + b * 60_000L
        val nValid = (nEnd - start) + MIN_FOCUS_AFTER_BREAK * 60_000L <= total * 60_000L
        return PhaseInfo(false, if (nValid) nStart - now else -1)
    }

    /** Call when the timer has run out. Returns true only the first time it really completes. */
    fun complete(c: Context): Boolean {
        val e = endAt(c)
        if (e == 0L || e > System.currentTimeMillis()) return false
        val total = totalMin(c)
        val focus = focusMinutes(total, breakEvery(c), breakLen(c))
        val start = startAt(c)
        val day = if (start > 0) Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
        else LocalDate.now()
        val attempts = try { JSONObject(p(c).getString("attempts", "{}") ?: "{}") } catch (x: Exception) { JSONObject() }
        val rec = JSONObject().put("d", day.toString()).put("f", focus).put("t", total).put("a", attempts)
        val arr = historyArr(c).put(rec)
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - 400) until arr.length()) trimmed.put(arr.get(i))
        p(c).edit().putString("history", trimmed.toString()).putString("attempts", "{}").putLong("end", 0L).apply()
        return true
    }

    // ---------- distraction attempts ----------
    fun logAttempt(c: Context, pkg: String) {
        val o = try { JSONObject(p(c).getString("attempts", "{}") ?: "{}") } catch (x: Exception) { JSONObject() }
        o.put(pkg, o.optInt(pkg, 0) + 1)
        p(c).edit().putString("attempts", o.toString()).apply()
    }

    // ---------- break preferences (last choice on the home screen) ----------
    fun prefBreakOn(c: Context): Boolean = p(c).getBoolean("pref_break_on", false)
    fun prefBreakEvery(c: Context): Int = p(c).getInt("pref_break_every", 90)
    fun prefBreakLen(c: Context): Int = p(c).getInt("pref_break_len", 10)
    fun savePrefBreak(c: Context, on: Boolean, every: Int, len: Int) {
        p(c).edit().putBoolean("pref_break_on", on).putInt("pref_break_every", every).putInt("pref_break_len", len).apply()
    }

    // ---------- history / report ----------
    private fun historyArr(c: Context): JSONArray =
        try { JSONArray(p(c).getString("history", "[]") ?: "[]") } catch (x: Exception) { JSONArray() }

    private fun focusByDay(c: Context): HashMap<LocalDate, Int> {
        val byDay = HashMap<LocalDate, Int>()
        val arr = historyArr(c)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = runCatching { LocalDate.parse(o.getString("d")) }.getOrNull() ?: continue
            byDay[d] = (byDay[d] ?: 0) + o.optInt("f")
        }
        for ((k, v) in p(c).all) {   // minutes saved by older versions
            if (k.startsWith("done_") && v is Int) {
                runCatching { LocalDate.parse(k.removePrefix("done_")) }.getOrNull()?.let { byDay[it] = (byDay[it] ?: 0) + v }
            }
        }
        return byDay
    }

    fun todayMinutes(c: Context): Int = focusByDay(c)[LocalDate.now()] ?: 0

    fun buildReport(c: Context): Report {
        val today = LocalDate.now()
        val byDay = focusByDay(c)
        val weekStart = today.minusDays(6)
        var sessions = 0
        val attempts = HashMap<String, Int>()
        val arr = historyArr(c)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = runCatching { LocalDate.parse(o.getString("d")) }.getOrNull() ?: continue
            if (d.isBefore(weekStart)) continue
            sessions++
            val a = o.optJSONObject("a") ?: continue
            for (k in a.keys()) attempts[k] = (attempts[k] ?: 0) + a.optInt(k)
        }
        val days = (6 downTo 0).map { n -> today.minusDays(n.toLong()).let { DayStat(it, byDay[it] ?: 0) } }
        var streak = 0
        var d = if ((byDay[today] ?: 0) >= MIN_STREAK_DAY) today else today.minusDays(1)
        while ((byDay[d] ?: 0) >= MIN_STREAK_DAY) { streak++; d = d.minusDays(1) }
        val sorted = attempts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        return Report(days, days.sumOf { it.focusMin }, sessions, streak, byDay[today] ?: 0, sorted, sorted.sumOf { it.second })
    }
}
