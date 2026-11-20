package com.focusguard.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.statusBarHeight(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%02d:%02d".format(s / 60, s % 60)
}

/**
 * Small countdown pill, shown while an ALLOWED app is open and during breaks.
 * Sits in the status-bar area by default and can be dragged anywhere; the position is remembered.
 */
class TimerChip(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val prefs = svc.getSharedPreferences("fg", Context.MODE_PRIVATE)
    private var view: TextView? = null
    private var bg: GradientDrawable? = null

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val shape = GradientDrawable().apply {
            cornerRadius = svc.dp(16).toFloat()
            setColor(Color.parseColor("#FFFFFF"))
        }
        val tv = TextView(svc).apply {
            setTextColor(Color.parseColor("#2D1B69"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(svc.dp(10), svc.dp(3), svc.dp(10), svc.dp(3))
            alpha = 0.92f
            background = shape
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = prefs.getInt("chip_x", 0)
            y = prefs.getInt("chip_y", svc.dp(2))
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("chip_x", lp.x).putInt("chip_y", lp.y).apply()
            }
            true
        }
        runCatching { wm.addView(tv, lp) }.onSuccess {
            view = tv
            bg = shape
            update(formatTime(Session.remainingMs(svc)), false)
        }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        bg = null
    }

    /** breakMode = green chip with "Break mm:ss". */
    fun update(text: String, breakMode: Boolean) {
        view?.text = text
        bg?.setColor(Color.parseColor(if (breakMode) "#9BE8B8" else "#FFFFFF"))
    }
}

/** Countdown ring with the remaining time written inside. */
class RingView(ctx: Context) : View(ctx) {
    var fraction = 1f
    var main = ""
    var extra = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#40FFFFFF")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#FFFFFF")
    }
    private val tMain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF"); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val tSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF"); textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val w = s * 0.07f
        track.strokeWidth = w
        arc.strokeWidth = w
        val r = s / 2 - w
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, r, track)
        c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * fraction.coerceIn(0f, 1f), false, arc)
        tMain.textSize = s * (if (main.length > 5) 0.15f else 0.2f)
        c.drawText(main, cx, cy + s * 0.04f, tMain)
        tSub.textSize = s * 0.065f
        c.drawText("focus time left", cx, cy + s * 0.16f, tSub)
        if (extra.isNotEmpty()) {
            tSub.textSize = s * 0.058f
            c.drawText(extra, cx, cy + s * 0.25f, tSub)
        }
    }
}

/**
 * Full-screen lock screen drawn by the accessibility service. It covers the launcher,
 * recents and every non-allowed app, so there is nothing to tap or swipe away.
 * Shows clock on top, countdown in the middle, allowed apps at the bottom.
 */
class BlockerOverlay(private val svc: AccessibilityService, private val onLaunch: (String) -> Unit) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var clock: TextView? = null
    private var date: TextView? = null
    private var ring: RingView? = null

    private fun label(text: String, sp: Float, color: String, light: Boolean = false) = TextView(svc).apply {
        this.text = text
        setTextColor(Color.parseColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        if (light) typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private fun appView(item: AppItem): View = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        contentDescription = item.label
        addView(ImageView(svc).apply { setImageDrawable(item.icon) }, LinearLayout.LayoutParams(svc.dp(44), svc.dp(44)))
        // The overlay is visible while this runs, so Android allows the launch.
        // The overlay hides itself when the allowed app's window appears.
        setOnClickListener {
            onLaunch(item.pkg)
            runCatching { svc.startActivity(Intent(item.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun appRow(items: List<AppItem>): LinearLayout = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, svc.dp(6), 0, svc.dp(6))
        items.forEach { addView(appView(it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        repeat(5 - items.size) { addView(View(svc), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    fun show() {
        if (root != null) return
        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(svc.dp(24), svc.statusBarHeight() + svc.dp(24), svc.dp(24), svc.dp(36))
        }
        clock = label("", 52f, "#FFFFFF", true)
        date = label("", 14f, "#B3FFFFFF")
        col.addView(clock)
        col.addView(date)

        val box = FrameLayout(svc)
        ring = RingView(svc)   // draws a circle as large as the free space allows
        box.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom "dock": a soft glass box holding Phone + the chosen apps
        val dock = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(svc.dp(8), svc.dp(10), svc.dp(8), svc.dp(10))
            background = GradientDrawable().apply {
                cornerRadius = svc.dp(28).toFloat()
                setColor(Color.parseColor("#26FFFFFF"))
            }
        }
        Apps.launchItems(svc).chunked(5).forEach { dock.addView(appRow(it)) }
        col.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val frame = FrameLayout(svc).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1B1A21"), Color.parseColor("#252330"), Color.parseColor("#2E2A3B")),
            )
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { wm.addView(frame, lp) }.onSuccess {
            root = frame
            update(Session.remainingMs(svc))
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        clock = null
        date = null
        ring = null
    }

    fun update(remMs: Long, breakInMs: Long = -1) {
        val now = Date()
        clock?.text = SimpleDateFormat("hh:mm", Locale.getDefault()).format(now)
        date?.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        val total = (Session.totalMin(svc) * 60_000L).coerceAtLeast(1)
        ring?.apply {
            fraction = remMs.toFloat() / total
            main = formatTime(remMs)
            extra = if (breakInMs > 0) "break in " + formatTime(breakInMs) else ""
            invalidate()
        }
    }
}
