package com.focusguard.phone

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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
 * During a phone call (ringing or talking): nothing is ever covered, so you can always answer / hang up.
 */
class GuardService : AccessibilityService() {
    private lateinit var chip: TimerChip
    private lateinit var blocker: BlockerOverlay
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var onBreak = false
    private var wasInCall = false
    private var transitionUntil = 0L                       // ~1s after tapping an app on the lock screen
    private val tempAllowed = mutableSetOf<String>()       // helper packages of an app that was just launched
    private val lastAttempt = mutableMapOf<String, Long>() // debounce for the distraction report
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /** Ringing or talking on a normal phone call (no permission needed to read this). */
    private fun inCall(): Boolean {
        val m = runCatching { audio.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return m == AudioManager.MODE_RINGTONE || m == AudioManager.MODE_IN_CALL
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                tickOnce()
            } catch (e: Exception) {
                Diag.logCrash(this@GuardService, e)   // never let an error kill the service
            }
            if (ticking) handler.postDelayed(this, 1000)
        }
    }

    private fun tickOnce() {
        val rem = Session.remainingMs(this)
        if (rem <= 0) {
            endSession()
            return
        }
        if (rem <= 5000) Feedback.countdown(this)   // last 5 seconds: one buzz per second
        val ph = Session.phase(this)
        if (ph.onBreak != onBreak) {
            onBreak = ph.onBreak
            tempAllowed.clear()
            if (onBreak) { blocker.hide(); chip.show() } else showBlocker()
            Chime.play()
        }
        val call = inCall()
        if (call != wasInCall) {
            wasInCall = call
            if (call) { blocker.hide(); chip.show() } else if (!onBreak) showBlocker()
        }
        if (onBreak) {
            chip.update("Break " + formatTime(ph.untilChangeMs), true)
        } else {
            chip.update(formatTime(rem), false)
            blocker.update(rem, ph.untilChangeMs)
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
            runCatching { ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java)) }
            onBreak = Session.phase(this).onBreak
            wasInCall = inCall()
            if (onBreak || wasInCall) chip.show() else blocker.show()
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
        if (inCall()) {                      // never cover a call screen
            blocker.hide(); chip.show(); startTicking()
            return
        }
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
        wasInCall = false
        tempAllowed.clear()
        lastAttempt.clear()
        handler.removeCallbacks(tick)
        chip.hide()
        blocker.hide()
        runCatching { stopService(Intent(this, KeepAliveService::class.java)) }
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
        try {
            handleEvent(e)
        } catch (x: Exception) {
            Diag.logCrash(this, x)
        }
    }

    private fun handleEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Session.isActive(this)) {
            if (Session.endAt(this) > 0) endSession() else { chip.hide(); blocker.hide() }
            return
        }
        if (onBreak) {                         // breaks are free time, but not for uninstalling / switching protection off
            val p = e.packageName?.toString()
            if (p != null && p in Apps.TAMPER_PACKAGES) performGlobalAction(GLOBAL_ACTION_HOME)
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
        // A call is ringing or running: never cover its screen, whatever the phone's call app is called.
        if (inCall()) {
            blocker.hide(); chip.show()
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

    override fun onUnbind(intent: Intent?): Boolean {
        if (Session.isActive(this)) Diag.noteStop(this, "accessibility service was unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (Session.isActive(this)) Diag.noteStop(this, "accessibility service was stopped by the system")
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
