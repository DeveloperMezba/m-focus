package com.focusguard.phone

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * While a session is active: any window from an app that is not on the allowed list
 * is sent away (HOME) and the lock screen is brought back. Also owns the always-on-top timer chip.
 */
class GuardService : AccessibilityService() {
    private lateinit var overlay: TimerOverlay
    private var lastKick = 0L

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (Session.isActive(c)) {
                overlay.show()
                openLock()
            }
        }
    }

    override fun onServiceConnected() {
        overlay = TimerOverlay(this)
        ContextCompat.registerReceiver(
            this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Session.isActive(this)) overlay.show()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Session.isActive(this)) {
            Session.complete(this)
            overlay.hide()
            return
        }
        overlay.show()
        val pkg = e.packageName?.toString() ?: return
        if (pkg in Apps.allowedPackages(this)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        openLock()
    }

    private fun openLock() {
        val now = SystemClock.uptimeMillis()
        if (now - lastKick < 600) return
        lastKick = now
        startActivity(
            Intent(this, LockActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }
}

/** Small always-on-top countdown pill (accessibility overlay: needs no extra permission). */
class TimerOverlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            val rem = Session.remainingMs(svc)
            if (rem <= 0) {
                Session.complete(svc)
                hide()
                return
            }
            val s = rem / 1000
            view?.text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
            else "%02d:%02d".format(s / 60, s % 60)
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), svc.resources.displayMetrics
    ).toInt()

    fun show() {
        if (view != null) return
        val tv = TextView(svc).apply {
            setTextColor(Color.parseColor("#00382F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#7FD1C4"))
            }
        }
        val statusBar = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) svc.resources.getDimensionPixelSize(it) else dp(24) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBar + dp(4)
        }
        runCatching { wm.addView(tv, lp) }.onSuccess {
            view = tv
            handler.post(tick)
        }
    }

    fun hide() {
        handler.removeCallbacks(tick)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
