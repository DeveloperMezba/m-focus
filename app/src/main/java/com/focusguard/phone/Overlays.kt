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
import androidx.core.graphics.drawable.toBitmap
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

fun Context.navBarHeight(): Int {
    val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
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
        val dm = svc.resources.displayMetrics
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    // Keep the chip on screen: a position dragged into a corner is remembered forever.
                    lp.x = (startX + (e.rawX - downX).toInt()).coerceIn(-dm.widthPixels / 2, dm.widthPixels / 2)
                    lp.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, dm.heightPixels)
                    runCatching { wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("chip_x", lp.x).putInt("chip_y", lp.y).apply()
            }
            true
        }
        // Long-press puts the chip back in its default spot, in case it ever gets lost.
        tv.setOnLongClickListener {
            lp.x = 0
            lp.y = svc.dp(2)
            prefs.edit().putInt("chip_x", 0).putInt("chip_y", svc.dp(2)).apply()
            runCatching { wm.updateViewLayout(tv, lp) }
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
 * Shows clock on top, countdown in the middle, and a dock with 5 slots at the bottom:
 * [side][side][Phone][side][side]. Empty slots are shown but do nothing while a session runs.
 */
class BlockerOverlay(
    private val svc: AccessibilityService,
    private val onLaunch: (String) -> Unit,
    private val onReturnToCall: () -> Unit,
) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var clock: TextView? = null
    private var date: TextView? = null
    private var ring: RingView? = null
    private var callBtn: TextView? = null
    private var dockBox: LinearLayout? = null

    private fun label(text: String, sp: Float, color: String, light: Boolean = false) = TextView(svc).apply {
        this.text = text
        setTextColor(Color.parseColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        if (light) typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    /** Every slot is the same rounded square, whatever the app's own icon looks like. */
    private fun slotView(item: AppItem?): View {
        val tile = FrameLayout(svc).apply {
            background = GradientDrawable().apply {
                cornerRadius = svc.dp(16).toFloat()
                setColor(Color.parseColor(if (item != null) "#26FFFFFF" else "#14FFFFFF"))
                if (item == null) setStroke(svc.dp(1), Color.parseColor("#40FFFFFF"))
            }
        }
        if (item != null) {
            val bmp = item.icon?.let { runCatching { it.toBitmap(96, 96) }.getOrNull() }
            tile.contentDescription = item.label
            tile.addView(
                ImageView(svc).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(svc.dp(8), svc.dp(8), svc.dp(8), svc.dp(8))
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            // The overlay is visible while this runs, so Android allows the launch.
            // The overlay hides itself when the allowed app's window appears.
            tile.setOnClickListener {
                onLaunch(item.pkg)
                runCatching { svc.startActivity(Intent(item.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        } else {
            tile.addView(
                label("+", 22f, "#55FFFFFF"),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        return tile
    }

    private fun dockRow(slots: List<AppItem?>): LinearLayout = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        slots.forEach { item ->
            val cell = FrameLayout(svc)
            cell.addView(slotView(item), FrameLayout.LayoutParams(svc.dp(56), svc.dp(56), Gravity.CENTER))
            addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    fun show(inCall: Boolean = false) {
        if (root != null) return
        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(svc.dp(24), svc.statusBarHeight() + svc.dp(24), svc.dp(24), maxOf(svc.dp(36), svc.navBarHeight() + svc.dp(16)))
        }
        clock = label("", 52f, "#FFFFFF", true)
        date = label("", 14f, "#B3FFFFFF")
        col.addView(clock)
        col.addView(date)

        val box = FrameLayout(svc)
        ring = RingView(svc)   // draws a circle as large as the free space allows
        box.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // While a call is ringing or running: the only way back to it from here.
        val btn = label("Return to call", 15f, "#FF3B5C").apply {
            background = GradientDrawable().apply {
                cornerRadius = svc.dp(24).toFloat()
                setColor(Color.parseColor("#26FFFFFF"))
                setStroke(svc.dp(1), Color.parseColor("#66FF3B5C"))
            }
            setPadding(svc.dp(24), svc.dp(12), svc.dp(24), svc.dp(12))
            setOnClickListener { onReturnToCall() }
            visibility = if (inCall) View.VISIBLE else View.GONE
        }
        callBtn = btn
        col.addView(btn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = svc.dp(16) })

        // bottom "dock": a soft glass box with the 5 slots
        val dock = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(svc.dp(8), svc.dp(12), svc.dp(8), svc.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = svc.dp(28).toFloat()
                setColor(Color.parseColor("#26FFFFFF"))
            }
            // During a call the phone is for the call only: no app dock.
            visibility = if (inCall) View.GONE else View.VISIBLE
        }
        dockBox = dock
        dock.addView(dockRow(Apps.dockSlots(svc)))
        col.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val pal = Palettes[AppSettings.theme(svc)]
        val frame = FrameLayout(svc).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.lockTop, pal.lockMid, pal.lockBottom))
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
        callBtn = null
        dockBox = null
    }

    fun isShowing(): Boolean = root != null

    fun update(remMs: Long, breakInMs: Long = -1, inCall: Boolean = false) {
        val now = Date()
        clock?.text = SimpleDateFormat("hh:mm", Locale.getDefault()).format(now)
        date?.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        val total = (Session.totalMin(svc) * 60_000L).coerceAtLeast(1)
        ring?.apply {
            fraction = remMs.toFloat() / total
            main = formatTime(remMs)
            extra = if (inCall) "on a call" else if (breakInMs > 0) "break in " + formatTime(breakInMs) else ""
            invalidate()
        }
        callBtn?.visibility = if (inCall) View.VISIBLE else View.GONE
        dockBox?.visibility = if (inCall) View.GONE else View.VISIBLE
    }
}
