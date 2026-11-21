package com.focusguard.phone

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * During a focus phase:
 *  - an allowed app (Phone, extras) is open -> small timer chip
 *  - anything else (launcher, recents, blocked apps, this app) -> full-screen lock overlay
 * During a break: nothing is blocked; a green "Break mm:ss" chip is shown.
 * During a phone call: ONLY the call screen is usable. Everything else goes back to the focus
 * lock screen, which then shows a "Return to call" button so the call is never out of reach.
 */
class GuardService : AccessibilityService() {
    private lateinit var chip: TimerChip
    private lateinit var blocker: BlockerOverlay
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var onBreak = false
    private var wasInCall = false
    private var receiverRegistered = false
    private var transitionUntil = 0L                       // ~1s after tapping an app on the lock screen
    private val tempAllowed = mutableSetOf<String>()       // helper packages of an app that was just launched
    private val lastAttempt = mutableMapOf<String, Long>() // debounce for the distraction report
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /** Set right after "Return to call": the lock screen must never cover the call screen. */
    private var callReturnUntil = 0L

    /** When to check whether returning to the call screen actually worked (0 = nothing pending). */
    private var callVerifyAt = 0L

    /** Debounce: one "back to the call" attempt at a time. */
    private var lastReturnAt = 0L

    /** The overlay was hidden for the system lock screen and must come back after unlocking. */
    private var restoreAfterUnlock = false

    /** Last real app screen we saw, used to decide what to show when a call ends. */
    private var lastPkg: String? = null

    /** The in-call activity we last saw, used to bring the call screen back (see returnToCall). */
    private var callActivity: ComponentName? = null

    /** Ringing or talking on a normal phone call (no permission needed to read this). */
    private fun inCall(): Boolean {
        val m = runCatching { audio.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return m == AudioManager.MODE_RINGTONE || m == AudioManager.MODE_IN_CALL
    }

    /**
     * Some phones (and some OEM dialers) never change AudioManager mode, so a live "call"
     * notification from the call app counts too. Needs no extra permission: the notification
     * listener access is already granted for the focus session itself.
     */
    private fun callNotifying(): Boolean =
        runCatching { NotificationBlocker.instance?.hasCallNotification() == true }.getOrDefault(false)

    /** True while ringing or talking, however the phone reports it. */
    private fun callActive(): Boolean = inCall() || callNotifying()

    /**
     * Is this window the call screen? Besides the package/class hints, a window that owns a
     * "call" notification is the call screen too — that covers OEM dialers we don't know by name.
     * Blocked apps (WhatsApp/Telegram voice calls, ...) never qualify.
     */
    private fun isCallWindow(pkg: String, cls: String): Boolean {
        if (pkg in Apps.BLOCKED || pkg in Apps.AI_ALL) return false
        if (Apps.isCallUi(this, pkg, cls)) return true
        if (runCatching { pkg in (NotificationBlocker.instance?.callPackages() ?: emptySet()) }.getOrDefault(false)) {
            return true
        }
        // Last resort for unknown OEM in-call screens: a pre-installed package with no launcher
        // icon, i.e. something the user could not have opened as a normal app.
        return Apps.isSystemPackage(this, pkg) &&
            runCatching { packageManager.getLaunchIntentForPackage(pkg) == null }.getOrDefault(false) &&
            pkg !in Apps.TAMPER_PACKAGES &&
            pkg !in Apps.launcherPackages(this) &&
            pkg !in Apps.browserPackages(this)
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
        val call = callActive()
        if (rem <= 5000 && !call) Feedback.countdown(this)   // last 5 seconds: one buzz per second
        val ph = Session.phase(this)
        if (ph.onBreak != onBreak) {
            onBreak = ph.onBreak
            tempAllowed.clear()
            if (onBreak) {
                blocker.hide(); chip.show()
            } else {
                // Break over: notifications that came in during the break must go too.
                NotificationBlocker.instance?.sweep()
                showBlocker()
            }
            if (!call) Chime.play()          // never play a sound into a running call
        }
        if (call != wasInCall) {
            wasInCall = call
            if (call) {
                // The call screen is about to come to the front: never cover it. As soon as the
                // user leaves it, the window events put the focus lock back (see handleEvent).
                if (!onBreak) { blocker.hide(); chip.show() }
            } else if (!onBreak) {
                // Call over: back to the normal rule (chip in an allowed app, lock everywhere else).
                if (isAllowed(lastPkg)) showChip() else showBlocker()
            }
        }
        if (onBreak) {
            chip.update("Break " + formatTime(ph.untilChangeMs), true)
        } else {
            chip.update(formatTime(rem), false)
            blocker.update(rem, ph.untilChangeMs, call)
        }
        // While the system lock screen is up the overlay stays hidden; put it back once unlocked.
        if (restoreAfterUnlock && !keyguardLocked()) {
            restoreAfterUnlock = false
            if (!onBreak && !call && !blocker.isShowing()) {
                if (isAllowed(lastPkg)) showChip() else showBlocker()
            }
        }
        checkCallReturn()
    }

    private fun isAllowed(pkg: String?): Boolean =
        pkg != null && (pkg in Apps.allowedApps(this) || pkg in tempAllowed)

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!Session.isActive(c)) return
            // Never cover the system lock screen, or the phone could not be unlocked.
            if (keyguardLocked()) { hideAll(); return }
            when {
                onBreak -> chip.show()
                callActive() && isCallWindow(lastPkg ?: "", "") -> chip.show()
                isAllowed(lastPkg) -> showChip()
                else -> showBlocker()
            }
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            hideAll()      // the system lock screen takes over while the display is off
        }
    }

    private fun keyguardLocked(): Boolean = runCatching {
        getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    private fun hideAll() {
        runCatching { chip.hide() }
        runCatching { blocker.hide() }
        restoreAfterUnlock = true
    }

    override fun onServiceConnected() {
        // The system can bind the service again after it was restarted: never stack overlays.
        if (::chip.isInitialized) runCatching { chip.hide() }
        if (::blocker.isInitialized) runCatching { blocker.hide() }
        chip = TimerChip(this)
        blocker = BlockerOverlay(this, { pkg ->
            transitionUntil = SystemClock.uptimeMillis() + 800
            tempAllowed.clear()
            tempAllowed += Apps.HELPERS[pkg].orEmpty()
        }, { returnToCall(fallbackToLock = true) })
        instance = this
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        if (Session.isActive(this)) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java)) }
            onBreak = Session.phase(this).onBreak
            wasInCall = callActive()
            when {
                onBreak -> chip.show()
                wasInCall -> { /* let the call screen stay visible */ }
                else -> blocker.show()
            }
            startTicking()
        }
    }

    /**
     * A call notification just appeared. AudioManager mode changes a moment later, so react here
     * to uncover the call screen immediately instead of waiting for the next tick.
     */
    fun onCallNotification() {
        if (onBreak || !Session.isActive(this)) return
        handler.post {
            if (onBreak) return@post
            wasInCall = true
            blocker.hide()
            chip.show()
            startTicking()
        }
    }

    /** Called by MainActivity right after a session starts. */
    fun onSessionStarted() {
        onBreak = false
        tempAllowed.clear()
        if (callActive()) {                   // a session started during a call: keep the call usable
            blocker.hide(); chip.show(); startTicking()
        } else {
            showBlocker()
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    private fun showBlocker(force: Boolean = false) {
        if (onBreak) return
        // Going back to the call: the call screen is already coming up, don't jump in front of it.
        if (!force && SystemClock.uptimeMillis() < callReturnUntil && callActive()) return
        // During a call the call screen itself is the only exception; the lock screen is fine to
        // show on top of everything else (including the launcher) and offers "Return to call".
        chip.hide()
        val call = callActive()
        blocker.show(call)
        blocker.update(Session.remainingMs(this), Session.phase(this).untilChangeMs, call)
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
        lastPkg = null
        restoreAfterUnlock = false
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
        // A call is ringing or running: only the call screen stays usable. Going home, to recents
        // or to any other app sends you back to the call instead, so a call can't be used as a
        // way out of a focus session; if that fails, the focus lock screen appears (with a
        // "Return to call" button).
        if (callActive()) {
            when {
                isCallWindow(pkg, cls) -> {
                    rememberCallUi(pkg, cls)
                    lastPkg = pkg
                    showChip()
                }
                // Notification shade, keyboards, permission dialogs: they never decide the call
                // state; keep the lock screen visible if it already is (so the shade can't be
                // used as a way into a blocked app), otherwise leave the screen alone.
                pkg in Apps.ignoredPackages(this) -> {
                    if (blocker.isShowing() && !isCallWindow(pkg, cls)) {
                        blocker.update(Session.remainingMs(this), Session.phase(this).untilChangeMs, true)
                    }
                }
                else -> {
                    lastPkg = pkg
                    tempAllowed.clear()
                    returnToCall(fallbackToLock = true)
                }
            }
            return
        }
        // Recents is drawn by systemui on some phones, but it must not be usable during focus.
        if (cls.lowercase().contains("recents")) {
            tempAllowed.clear()
            showBlocker()
            return
        }
        // Notification shade, keyboard, permission dialogs: keep the current state.
        if (pkg in Apps.ignoredPackages(this)) return
        lastPkg = pkg
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

    // ------------------------------------------------------------------ call screen
    private fun rememberCallUi(pkg: String, cls: String) {
        if (cls.isEmpty()) return
        val c = ComponentName(pkg, cls)
        if (callActivity != c && runCatching { packageManager.getActivityInfo(c, 0) }.isSuccess) callActivity = c
    }

    /**
     * Brings the ongoing call back to the front. Everything is best effort, in order of reliability:
     * the call notification's own intent, the in-call screen we saw, then the dialer.
     * The lock screen is removed first, so it can never end up covering the call screen.
     * With [fallbackToLock], the focus lock screen is shown when nothing worked.
     */
    private fun returnToCall(fallbackToLock: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (now - lastReturnAt < 700) return   // never loop on repeated window events
        lastReturnAt = now
        callReturnUntil = now + 2500
        blocker.hide()
        chip.show()
        val pi = NotificationBlocker.instance?.callIntent()
        if (pi != null && sendPendingIntent(pi)) {
            verifyCallReturn(fallbackToLock)
            return
        }
        callActivity?.let { c ->
            val exists = runCatching { packageManager.getActivityInfo(c, 0) }.isSuccess
            if (exists && runCatching {
                    startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            ) {
                verifyCallReturn(fallbackToLock)
                return
            }
        }
        runCatching { getSystemService(TelecomManager::class.java)?.showInCallScreen(false) }
        val dialer = runCatching {
            startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (dialer) {
            verifyCallReturn(fallbackToLock)
        } else if (fallbackToLock || !callActive()) {
            showBlocker()          // nothing worked: lock the screen instead of a bare launcher
        }
    }

    /**
     * If the call screen did not actually come back, show the focus lock screen instead of
     * leaving the user on the launcher (that would end protection).
     */
    private fun verifyCallReturn(fallbackToLock: Boolean) {
        if (!fallbackToLock) return
        callVerifyAt = SystemClock.uptimeMillis() + 1200
    }

    private fun checkCallReturn() {
        if (callVerifyAt == 0L) return
        if (SystemClock.uptimeMillis() < callVerifyAt) return
        callVerifyAt = 0L
        // The call screen never came back: show the lock screen so protection is not lost.
        if (callActive() && !isCallWindow(lastPkg ?: "", "")) {
            callReturnUntil = 0L
            showBlocker(force = true)
        }
    }

    private fun sendPendingIntent(pi: PendingIntent): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 34) {
            // Opt in to the background-activity-launch privilege while sending, otherwise the
            // call screen may be refused to start on Android 14+.
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
            pi.send(this, 0, null, null, null, null, opts)
        } else {
            pi.send()
        }
    }.isSuccess

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (Session.isActive(this)) Diag.noteStop(this, "accessibility service was unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (Session.isActive(this)) Diag.noteStop(this, "accessibility service was stopped by the system")
        if (instance === this) instance = null
        handler.removeCallbacks(tick)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenOn) }
            runCatching { unregisterReceiver(screenOff) }
            receiverRegistered = false
        }
        if (::chip.isInitialized) chip.hide()
        if (::blocker.isInitialized) blocker.hide()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: GuardService? = null
    }
}
