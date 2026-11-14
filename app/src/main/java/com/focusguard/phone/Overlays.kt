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

/** Small always-on-top countdown pill. Shown only while an ALLOWED app (Phone, AI...) is open. */
class TimerChip(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    fun show() {
        if (view != null) return
        val tv = TextView(svc).apply {
            setTextColor(Color.parseColor("#00382F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(svc.dp(14), svc.dp(6), svc.dp(14), svc.dp(6))
            background = GradientDrawable().apply {
                cornerRadius = svc.dp(20).toFloat()
                setColor(Color.parseColor("#7FD1C4"))
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = svc.statusBarHeight() + svc.dp(4)
        }
        runCatching { wm.addView(tv, lp) }.onSuccess {
            view = tv
            update(Session.remainingMs(svc))
        }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    fun update(remMs: Long) {
        view?.text = formatTime(remMs)
    }
}

/** Countdown ring with the remaining time written inside. */
class RingView(ctx: Context) : View(ctx) {
    var fraction = 1f
    var main = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#262D36")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#7FD1C4")
    }
    private val tMain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E1E3E8"); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val tSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A929E"); textAlign = Paint.Align.CENTER
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
    }
}

/**
 * Full-screen lock screen drawn by the accessibility service. It covers the launcher,
 * recents and every non-allowed app, so there is nothing to tap or swipe away.
 * Shows clock on top, countdown in the middle, allowed apps at the bottom.
 */
class BlockerOverlay(private val svc: AccessibilityService) {
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
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(svc).apply { setImageDrawable(item.icon) }, LinearLayout.LayoutParams(svc.dp(52), svc.dp(52)))
        addView(label(item.label, 12f, "#E1E3E8").apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // The overlay is visible while this runs, so Android allows the launch.
        // The overlay hides itself when the allowed app's window appears.
        setOnClickListener {
            runCatching { svc.startActivity(Intent(item.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun appRow(items: List<AppItem>): LinearLayout = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, svc.dp(6), 0, svc.dp(6))
        items.forEach { addView(appView(it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        repeat(4 - items.size) { addView(View(svc), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    fun show() {
        if (root != null) return
        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(svc.dp(24), svc.statusBarHeight() + svc.dp(24), svc.dp(24), svc.dp(36))
        }
        clock = label("", 64f, "#E1E3E8", true)
        date = label("", 14f, "#8A929E")
        col.addView(clock)
        col.addView(date)

        val box = FrameLayout(svc)
        val side = (svc.resources.displayMetrics.widthPixels * 0.72f).toInt()
        ring = RingView(svc)
        box.addView(ring, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(label("Allowed", 13f, "#8A929E").apply { setPadding(0, 0, 0, svc.dp(10)) })
        Apps.launchItems(svc).chunked(4).forEach { col.addView(appRow(it)) }

        val frame = FrameLayout(svc).apply {
            setBackgroundColor(Color.parseColor("#0F1419"))
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

    fun update(remMs: Long) {
        val now = Date()
        clock?.text = SimpleDateFormat("hh:mm", Locale.getDefault()).format(now)
        date?.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        val total = (Session.totalMin(svc) * 60_000L).coerceAtLeast(1)
        ring?.apply {
            fraction = remMs.toFloat() / total
            main = formatTime(remMs)
            invalidate()
        }
    }
}
