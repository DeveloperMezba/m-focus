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
 * During a session:
 *  - an allowed app (Phone, AI, extras) is open  -> small timer chip on top
 *  - anything else (launcher, recents, blocked apps, this app) -> full-screen lock overlay
 * Never both at once.
 */
class GuardService : AccessibilityService() {
    private lateinit var chip: TimerChip
    private lateinit var blocker: BlockerOverlay
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var transitionUntil = 0L                // ~1s after tapping an app on the lock screen
    private val tempAllowed = mutableSetOf<String>() // helper packages of an app that was just launched

    private val tick = object : Runnable {
        override fun run() {
            val rem = Session.remainingMs(this@GuardService)
            if (rem <= 0) {
                endSession()
                return
            }
            chip.update(rem)
            blocker.update(rem)
            handler.postDelayed(this, 1000)
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (Session.isActive(c)) showBlocker()
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
        if (Session.isActive(this)) showBlocker()
    }

    /** Called by MainActivity right after a session starts. */
    fun onSessionStarted() = showBlocker()

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    private fun showBlocker() {
        chip.hide()
        blocker.show()
        startTicking()
    }

    private fun showChip() {
        blocker.hide()
        chip.show()
        startTicking()
    }

    private fun endSession() {
        val finished = Session.complete(this)
        ticking = false
        tempAllowed.clear()
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

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Session.isActive(this)) {
            if (Session.endAt(this) > 0) endSession() else { chip.hide(); blocker.hide() }
            return
        }
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
            else -> { Session.logBlocked(this, pkg); showBlocker() }
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
