package com.focusguard.phone

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * During a focus phase:
 *  - an allowed app (Phone, extras) is open -> small timer chip
 *  - anything else (launcher, recents, blocked apps, this app) -> full-screen lock overlay
 * During a break: nothing is blocked; a green "Break mm:ss" chip is shown.
 */
class GuardService : AccessibilityService() {
    private lateinit var chip: TimerChip
    private lateinit var blocker: BlockerOverlay
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var onBreak = false
    private var transitionUntil = 0L                       // ~1s after tapping an app on the lock screen
    private val tempAllowed = mutableSetOf<String>()       // helper packages of an app that was just launched
    private val lastAttempt = mutableMapOf<String, Long>() // debounce for the distraction report

    private val tick = object : Runnable {
        override fun run() {
            val rem = Session.remainingMs(this@GuardService)
            if (rem <= 0) {
                endSession()
                return
            }
            val ph = Session.phase(this@GuardService)
            if (ph.onBreak != onBreak) {
                onBreak = ph.onBreak
                tempAllowed.clear()
                if (onBreak) { blocker.hide(); chip.show() } else showBlocker()
                Chime.play()
            }
            if (onBreak) {
                chip.update("Break " + formatTime(ph.untilChangeMs), true)
            } else {
                chip.update(formatTime(rem), false)
                blocker.update(rem, ph.untilChangeMs)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!Session.isActive(c)) return
            if (onBreak) chip.show() else showBlocker()
        }
    }

    override fun onServiceConnected() {
        chip = TimerChip(this)
        blocker = BlockerOverlay(this) { pkg ->
            transitionUntil = SystemClock.uptimeMillis() + 800
            tempAllowed.clear()
            tempAllowed += Apps.HELPERS[pkg].orEmpty()
        }
        instance = this
        ContextCompat.registerReceiver(
            this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Session.isActive(this)) {
            onBreak = Session.phase(this).onBreak
            if (onBreak) chip.show() else blocker.show()
            startTicking()
        }
    }

    /** Called by MainActivity right after a session starts. */
    fun onSessionStarted() {
        onBreak = false
        tempAllowed.clear()
        showBlocker()
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    private fun showBlocker() {
        if (onBreak) return
        chip.hide()
        blocker.show()
        startTicking()
    }

    private fun showChip() {
        if (onBreak) return
        blocker.hide()
        chip.show()
        startTicking()
    }

    private fun endSession() {
        val finished = Session.complete(this)
        ticking = false
        onBreak = false
        tempAllowed.clear()
        lastAttempt.clear()
        handler.removeCallbacks(tick)
        chip.hide()
        blocker.hide()
        if (finished) Chime.play()
    }

    /** The power / restart menu must not be usable during a session. */
    private fun isPowerMenu(pkg: String, cls: String): Boolean {
        if (!(pkg == "android" || pkg.contains("systemui") || pkg.contains("globalactions"))) return false
        val c = cls.lowercase()
        return listOf("globalactions", "powermenu", "poweroff", "shutdown", "rebootdialog", "restart").any { c.contains(it) }
    }

    /** Counts "tried to open X" for the report (only real, launchable apps; same app at most every 4 s). */
    private fun recordAttempt(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - (lastAttempt[pkg] ?: 0L) < 4000) return
        lastAttempt[pkg] = now
        if (packageManager.getLaunchIntentForPackage(pkg) == null) return
        Session.logAttempt(this, pkg)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Session.isActive(this)) {
            if (Session.endAt(this) > 0) endSession() else { chip.hide(); blocker.hide() }
            return
        }
        if (onBreak) return                    // breaks are free time
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        // Events raised by our own overlay windows are not real app switches.
        if (pkg == packageName && !cls.startsWith(packageName)) return
        if (isPowerMenu(pkg, cls)) {
            // Lock the screen (display off) so the menu disappears and Restart can't be tapped.
            if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            else performGlobalAction(GLOBAL_ACTION_HOME)
            tempAllowed.clear()
            showBlocker()
            return
        }
        // Notification shade, keyboard, permission dialogs: keep the current state.
        if (pkg in Apps.ignoredPackages(this)) return
        val isLauncher = pkg in Apps.launcherPackages(this)
        when {
            pkg in Apps.allowedApps(this) || pkg in tempAllowed -> showChip()
            // Right after a tap the launcher / this app may still report the old screen: ignore that for <1s.
            SystemClock.uptimeMillis() < transitionUntil && (isLauncher || pkg == packageName) -> {}
            // Home, recents, app drawer: always back to the lock screen.
            isLauncher -> { tempAllowed.clear(); showBlocker() }
            else -> { recordAttempt(pkg); showBlocker() }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screenOn) }
        if (::chip.isInitialized) chip.hide()
        if (::blocker.isInitialized) blocker.hide()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: GuardService? = null
    }
}
