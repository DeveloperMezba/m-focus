package com.focusguard.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TickSound.init(applicationContext)
        setContent {
            FocusTheme {
                Surface(Modifier.fillMaxSize().background(AppGradient), color = Color.Transparent, contentColor = Color.White) {
                    var tab by remember { mutableIntStateOf(0) }
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            if (tab == 0) {
                                Home(refresh) {
                                    GuardService.instance?.onSessionStarted()
                                    finishAndRemoveTask()
                                }
                            } else {
                                ReportScreen(refresh)
                            }
                        }
                        BottomTabs(tab) { tab = it }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Session.isActive(this)) {
            GuardService.instance?.onSessionStarted()
            finishAndRemoveTask()
        }
        refresh++
    }
}

fun a11yEnabled(c: Context): Boolean {
    val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(c, GuardService::class.java)
    return enabled.split(':').any {
        it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
    }
}

fun batteryOk(c: Context): Boolean =
    c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

fun fmtHM(min: Int): String = if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"

// ---------------------------------------------------------------- shared pieces

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0x33000000)).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TabItem("Focus", 0, selected == 0, onSelect)
        TabItem("Report", 1, selected == 1, onSelect)
    }
}

@Composable
fun TabItem(label: String, index: Int, selected: Boolean, onSelect: (Int) -> Unit) {
    val c = if (selected) Color.White else Color(0x99FFFFFF)
    Column(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color(0x33FFFFFF) else Color.Transparent)
            .clickable { onSelect(index) }
            .padding(horizontal = 30.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(22.dp)) {
            if (index == 0) {
                drawCircle(c, size.minDimension / 2 - 3f, style = Stroke(4f))
                drawCircle(c, 3f)
            } else {
                val bw = size.width * 0.22f
                listOf(0.45f, 0.75f, 1f).forEachIndexed { i, f ->
                    drawRoundRect(
                        c, Offset(i * (bw + size.width * 0.12f), size.height * (1 - f)),
                        Size(bw, size.height * f), CornerRadius(3f),
                    )
                }
            }
        }
        Text(label, color = c, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- Focus tab

@Composable
fun Home(refresh: Int, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    var minutes by remember { mutableIntStateOf(50) }
    var showPicker by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var breakOn by remember { mutableStateOf(Session.prefBreakOn(ctx)) }
    var breakEvery by remember { mutableIntStateOf(Session.prefBreakEvery(ctx)) }
    var breakLen by remember { mutableIntStateOf(Session.prefBreakLen(ctx)) }

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }   // notice permission changes while the app is open
    // "Listed in settings" is not enough: the service must actually be connected.
    val a11y = remember(refresh, tick) { a11yEnabled(ctx) && GuardService.instance != null }
    val battery = remember(refresh, tick) { batteryOk(ctx) }
    val notif = remember(refresh, tick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val extras = remember(refresh, showPicker) { Apps.effectiveExtras(ctx).size }
    val today = remember(refresh) { Session.todayMinutes(ctx) }
    val ready = a11y && notif && battery
    val breakWorks = breakOn && Session.hasBreak(minutes, breakEvery, breakLen)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("M Focus", fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${today / 60} h ${today % 60} min today", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!a11y) SetupRow(
            "1. Turn on accessibility",
            if (a11yEnabled(ctx)) "It stopped. Turn it off and on again." else "Needed to close blocked apps.",
            "Open settings",
        ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        if (!notif) SetupRow(
            "2. Allow notification access",
            "Needed to hide social notifications.",
            "Open settings",
        ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        if (!battery) SetupRow(
            "3. Disable battery optimization",
            "Keeps M Focus running.",
            "Allow",
        ) {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            )
        }

        Dial(minutes, { minutes = it }, Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(60 to "1 h", 180 to "3 h", 360 to "6 h", 420 to "7 h").forEach { (m, label) ->
                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
            }
        }

        // ---- Breaks: chosen here, together with the time, because it belongs to this session
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Breaks", fontWeight = FontWeight.Medium)
                    Text(
                        if (breakOn) "Every $breakEvery min, $breakLen min break" else "Off",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = breakOn, onCheckedChange = {
                    breakOn = it
                    Session.savePrefBreak(ctx, breakOn, breakEvery, breakLen)
                })
            }
            if (breakOn) {
                Text("Break every (min)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(30, 45, 60, 90, 120).forEach { v ->
                        FilterChip(selected = breakEvery == v, onClick = {
                            breakEvery = v
                            Session.savePrefBreak(ctx, breakOn, breakEvery, breakLen)
                        }, label = { Text("$v") })
                    }
                }
                Text("Break length (min)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20).forEach { v ->
                        FilterChip(selected = breakLen == v, onClick = {
                            breakLen = v
                            Session.savePrefBreak(ctx, breakOn, breakEvery, breakLen)
                        }, label = { Text("$v") })
                    }
                }
                if (!breakWorks) Text(
                    "This session is too short for a break.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                )
            }
        }

        GlassCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Always allowed", fontWeight = FontWeight.Medium)
                    Text("$extras / ${Apps.MAX_EXTRAS} chosen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton({ showPicker = true }) { Text("Choose apps") }
            }
        }

        Button(
            onClick = { confirm = true },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (ready) "Start focus session" else "Finish setup first", fontSize = 16.sp) }
    }

    if (showPicker) AppPicker { showPicker = false }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Start $minutes minutes?") },
        text = {
            Text(
                if (breakWorks) "Break every $breakEvery min for $breakLen min. You can't stop it early."
                else "You can't stop it early."
            )
        },
        confirmButton = {
            TextButton({
                confirm = false
                Session.start(ctx, minutes, if (breakWorks) breakEvery else 0, breakLen)
                NotificationBlocker.instance?.sweep()
                onStarted()
            }) { Text("Start") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

@Composable
fun SetupRow(title: String, desc: String, button: String, onClick: () -> Unit) {
    GlassCard {
        Text(title, fontWeight = FontWeight.Medium)
        Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick) { Text(button) }
    }
}

const val MAX_MIN = 480 // 8 hours

/** Clock-style dial: turn it like a minute hand. One full turn = 60 minutes, every minute is selectable.
 *  Every minute gives a soft click and a light tick vibration. */
@Composable
fun Dial(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tickMajor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickMinor = MaterialTheme.colorScheme.outline
    val current by rememberUpdatedState(minutes)
    val view = LocalView.current
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var last = 0.0
                var acc = 0.0
                fun angle(p: Offset): Double {
                    val d = p - Offset(size.width / 2f, size.height / 2f)
                    var a = Math.toDegrees(atan2(d.x.toDouble(), -d.y.toDouble()))
                    if (a < 0) a += 360.0
                    return a
                }
                detectDragGestures(onDragStart = { last = angle(it); acc = current.toDouble() }) { change, _ ->
                    val a = angle(change.position)
                    val delta = (a - last + 540.0) % 360.0 - 180.0   // shortest signed turn
                    last = a
                    acc = (acc + delta / 6.0).coerceIn(1.0, MAX_MIN.toDouble())  // 6 degrees = 1 minute
                    val m = Math.round(acc).toInt()
                    if (m != current) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        TickSound.play()
                        onChange(m)
                    }
                }
            }
        ) {
            val w = size.minDimension * 0.09f
            val r = size.minDimension / 2 - w * 1.8f
            val c = center
            for (i in 0 until 60) {
                val t = Math.toRadians(i * 6.0 - 90)
                val major = i % 5 == 0
                val r1 = r + w * 0.9f
                val r2 = r + w * (if (major) 1.7f else 1.4f)
                drawLine(
                    if (major) tickMajor else tickMinor,
                    Offset(c.x + r1 * cos(t).toFloat(), c.y + r1 * sin(t).toFloat()),
                    Offset(c.x + r2 * cos(t).toFloat(), c.y + r2 * sin(t).toFloat()),
                    strokeWidth = if (major) 4f else 2f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(track, r, c, style = Stroke(w))
            val sweep = if (minutes > 0 && minutes % 60 == 0) 360f else (minutes % 60) / 60f * 360f
            drawArc(primary, -90f, sweep, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r),
                style = Stroke(w, cap = StrokeCap.Round))
            val ang = Math.toRadians((sweep - 90f).toDouble())
            drawCircle(primary, w * 0.9f,
                Offset(c.x + r * cos(ang).toFloat(), c.y + r * sin(ang).toFloat()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (minutes < 60) "$minutes" else "%d:%02d".format(minutes / 60, minutes % 60),
                fontSize = 60.sp, fontWeight = FontWeight.Light,
            )
            Text(if (minutes < 60) "minutes" else "hours : minutes", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AppPicker(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember {
        val pm = ctx.packageManager
        val browsers = Apps.browserPackages(ctx)
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName && it.first !in Apps.BLOCKED && it.first !in Apps.AI_ALL && it.first !in browsers }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    var sel by remember { mutableStateOf(Apps.effectiveExtras(ctx)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Choose apps (${sel.size}/${Apps.MAX_EXTRAS})") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(apps) { (pkg, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { sel = if (pkg in sel) sel - pkg else if (sel.size < Apps.MAX_EXTRAS) sel + pkg else sel }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(pkg in sel, null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton({ Apps.setExtras(ctx, sel); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Report tab

fun appLabel(ctx: Context, pkg: String): String =
    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
        .getOrDefault(pkg)

@Composable
fun ReportScreen(refresh: Int) {
    val ctx = LocalContext.current
    val r = remember(refresh) { Session.buildReport(ctx) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Report", fontSize = 26.sp, fontWeight = FontWeight.Medium)

        GlassCard {
            Text("Last 7 days", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtHM(r.weekFocus), fontSize = 36.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(6.dp))
            WeekChart(r.days)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Streak", if (r.streak == 1) "1 day" else "${r.streak} days", Modifier.weight(1f))
            StatCard("Sessions", "${r.weekSessions}", Modifier.weight(1f))
            StatCard("Today", fmtHM(r.todayFocus), Modifier.weight(1f))
        }

        GlassCard {
            Text("Distractions (7 days)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.attemptsTotal == 0) {
                Text("No attempts. Nice focus!", fontSize = 18.sp)
            } else {
                Text("${r.attemptsTotal} tries to open other apps", fontSize = 18.sp)
                r.attempts.take(5).forEach { (pkg, count) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(appLabel(ctx, pkg), Modifier.weight(1f))
                        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text(
            "A day counts for the streak with 10+ focus minutes. Break time is not counted.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        )
    }
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun WeekChart(days: List<DayStat>) {
    val maxMin = maxOf(60, days.maxOf { it.focusMin })
    val dim = Color(0x22FFFFFF)
    Column {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val slot = size.width / days.size
            val bw = slot * 0.5f
            days.forEachIndexed { i, d ->
                val x = i * slot + (slot - bw) / 2
                drawRoundRect(dim, Offset(x, 0f), Size(bw, size.height), CornerRadius(bw / 2))
                if (d.focusMin > 0) {
                    val h = maxOf(size.height * d.focusMin / maxMin, bw)
                    drawRoundRect(
                        if (i == days.lastIndex) Color.White else Color(0xB3FFFFFF),
                        Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 2),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEach {
                Text(
                    it.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH),
                    Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
