package com.focusguard.phone

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
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
        blocker = BlockerOverlay(this)
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
        Session.complete(this)
        ticking = false
        handler.removeCallbacks(tick)
        chip.hide()
        blocker.hide()
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
        // Notification shade, keyboard, permission dialogs: keep the current state.
        if (pkg in Apps.ignoredPackages(this)) return
        if (pkg in Apps.allowedApps(this)) showChip() else showBlocker()
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
